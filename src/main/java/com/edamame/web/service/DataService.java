package com.edamame.web.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.edamame.security.db.DbService;
import static com.edamame.security.db.DbService.*;
import com.edamame.security.tools.AppLogger;
/**
 * データサービスクラス
 * データベースからの情報取得とWebフロントエンド向けデータ処理を担当
 */
public class DataService {
    

    /**
     * コンストラクタ
     */
    public DataService() {
    }

    /**
     * ダッシュボード統計情報を取得
     * @return 統計情報のMap
     */
    public Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new HashMap<>();

        try {
            // 総アクセス数（今日）
            stats.put("totalAccess", getTotalAccessToday());

            // 攻撃検知数（今日）
            stats.put("totalAttacks", getTotalAttacksToday());

            // ModSecurityブロック数（今日）
            stats.put("modsecBlocks", getModSecBlocksToday());

            // サーバーごとの統計データ
            stats.put("serverStats", getServerStats());

            // アクティブサーバー数
            stats.put("activeServers", getActiveServerCount());

            // 最新アラート
            stats.put("recentAlerts", getRecentAlerts(10));

            // サーバー一覧
            stats.put("serverList", getServerList());

            // 攻撃タイプ別統計
            stats.put("attackTypes", getAttackTypeStats());

            AppLogger.debug("ダッシュボード統計情報取得完了");

        } catch (Exception e) {
            AppLogger.error("ダッシュボード統計情報取得エラー: " + e.getMessage());
            stats.put("error", "統計情報の取得に失敗しました");
        }

        return stats;
    }

    /**
     * 今日の総アクセス数を取得
     * @return アクセス数
     */
    private int getTotalAccessToday() {
        String sql = "SELECT COUNT(*) FROM access_log WHERE DATE(access_time) = CURDATE()";
        return executeCountQuery(sql);
    }

    /**
     * 今日の攻撃検知数を取得
     * @return 攻撃検知数
     */
    private int getTotalAttacksToday() {
        String sql = """
            SELECT COUNT(DISTINCT al.id)
            FROM access_log al
            JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url
            WHERE DATE(al.access_time) = CURDATE()
            AND ur.attack_type NOT IN ('CLEAN', 'UNKNOWN', 'normal')
            """;
        return executeCountQuery(sql);
    }

    /**
     * 今日のModSecurityブロック数を取得
     * @return ブロック数
     */
    private int getModSecBlocksToday() {
        String sql = "SELECT COUNT(*) FROM access_log WHERE DATE(access_time) = CURDATE() AND blocked_by_modsec = TRUE";
        return executeCountQuery(sql);
    }

    /**
     * アクティブサーバー数を取得
     * @return サーバー数
     */
    private int getActiveServerCount() {
        String sql = "SELECT COUNT(*) FROM servers WHERE is_active = TRUE";
        return executeCountQuery(sql);
    }

    /**
     * COUNT系クエリを実行
     * @param sql SQLクエリ
     * @return カウント結果
     */
    private int executeCountQuery(String sql) {
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            AppLogger.error("カウントクエリ実行エラー: " + e.getMessage());
        }
        return 0;
    }

    /**
     * 最新アラート情報を取得
     * @param limit 取得件数
     * @return アラート情報のリスト
     */
    public List<Map<String, Object>> getRecentAlerts(int limit) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        String sql = """
            SELECT server_name, alert_type, severity, message, source_ip, target_url, 
                   rule_id, is_resolved, created_at
            FROM alerts 
            ORDER BY created_at DESC 
            LIMIT ?
            """;
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> alert = new HashMap<>();
                    alert.put("serverName", rs.getString("server_name"));
                    alert.put("alertType", rs.getString("alert_type"));
                    alert.put("accessTime", formatDateTime(rs.getTimestamp("created_at"))); // created_atを使用
                    alert.put("ipAddress", rs.getString("source_ip"));
                    alert.put("url", rs.getString("target_url"));
                    alert.put("attackType", rs.getString("alert_type")); // alert_typeを攻撃タイプとして使用
                    alert.put("ruleId", rs.getString("rule_id"));
                    alert.put("severity", rs.getString("severity"));
                    alert.put("isResolved", rs.getBoolean("is_resolved"));
                    alert.put("message", rs.getString("message"));
                    alert.put("severityLevel", determineSeverityLevel(rs.getString("alert_type"), rs.getString("severity")));

                    alerts.add(alert);
                }
            }
        } catch (SQLException e) {
            AppLogger.error("最新アラート取得エラー: " + e.getMessage());
        }
        return alerts;
    }

    /**
     * サーバー一覧を取得（照合順序対応版）
     * @return サーバー情報のリスト
     */
    public List<Map<String, Object>> getServerList() {
        List<Map<String, Object>> servers = new ArrayList<>();
        String sql = """
            SELECT s.*, 
                   COALESCE((SELECT COUNT(*) FROM access_log a 
                            WHERE a.server_name COLLATE utf8mb4_unicode_ci = s.server_name COLLATE utf8mb4_unicode_ci
                            AND DATE(a.access_time) = CURDATE()), 0) as today_access_count
            FROM servers s 
            ORDER BY s.server_name COLLATE utf8mb4_unicode_ci
            """;
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> server = new HashMap<>();
                server.put("name", rs.getString("server_name"));
                server.put("description", rs.getString("server_description"));
                server.put("isActive", rs.getBoolean("is_active"));
                server.put("lastLogReceived", formatDateTime(rs.getTimestamp("last_log_received")));
                server.put("todayAccessCount", rs.getInt("today_access_count"));
                server.put("status", determineServerStatus(rs.getBoolean("is_active"), rs.getTimestamp("last_log_received")));

                servers.add(server);
            }
            AppLogger.debug("サーバー一覧取得完了（照合順序対応版）: " + servers.size() + "台");
        } catch (SQLException e) {
            AppLogger.error("サーバー一覧取得エラー（照合順序修正版）: " + e.getMessage());
        }
        return servers;
    }

    /**
     * サーバーごとの統計データを取得
     * @return サーバー統計のリスト
     */
    public List<Map<String, Object>> getServerStats() {
        List<Map<String, Object>> serverStats = new ArrayList<>();
        String sql = "SELECT * FROM server_stats";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> stat = new HashMap<>();
                stat.put("serverName", rs.getString("server_name"));
                stat.put("serverDescription", rs.getString("server_description"));
                stat.put("isActive", rs.getBoolean("is_active"));
                stat.put("lastLogReceived", formatDateTime(rs.getTimestamp("last_log_received")));
                stat.put("totalAccess", rs.getInt("total_access"));
                stat.put("attackCount", rs.getInt("attack_count"));
                stat.put("modsecBlocks", rs.getInt("modsec_blocks"));
                stat.put("status", determineServerStatus(rs.getBoolean("is_active"), rs.getTimestamp("last_log_received")));

                serverStats.add(stat);
            }
            AppLogger.debug("サーバー統計データ取得完了: " + serverStats.size() + "台");
        } catch (SQLException e) {
            AppLogger.error("サーバー統計データ取得エラー: " + e.getMessage());
        }
        return serverStats;
    }

    /**
     * 攻撃タイプ別統計を取得
     * @return 攻撃タイプ統計のリスト
     */
    public List<Map<String, Object>> getAttackTypeStats() {
        List<Map<String, Object>> attackTypes = new ArrayList<>();
        String sql = "SELECT attack_type, COUNT(*) as count FROM url_registry GROUP BY attack_type";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> type = new HashMap<>();
                type.put("type", rs.getString("attack_type"));
                type.put("count", rs.getInt("count"));
                type.put("description", getAttackTypeDescription(rs.getString("attack_type")));

                attackTypes.add(type);
            }
        } catch (SQLException e) {
            AppLogger.error("攻撃タイプ統計取得エラー: " + e.getMessage());
        }
        return attackTypes;
    }

    /**
     * API用の統計データを取得
     * @return JSON用統計データ
     */
    public Map<String, Object> getApiStats() {
        Map<String, Object> apiStats = new HashMap<>();
        try {
            apiStats.put("totalAccess", getTotalAccessToday());
            apiStats.put("totalAttacks", getTotalAttacksToday());
            apiStats.put("modsecBlocks", getModSecBlocksToday());
            apiStats.put("activeServers", getActiveServerCount());
            apiStats.put("lastUpdate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        } catch (Exception e) {
            AppLogger.error("API統計データ取得エラー: " + e.getMessage());
        }
        return apiStats;
    }

    /**
     * 日時をフォーマット
     * @param timestamp SQLタイムスタンプ
     * @return フォーマット済み日時文字列
     */
    private String formatDateTime(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return "未記録";
        }
        return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * 攻撃レベルを判定
     * @param attackType 攻撃タイプ
     * @param severity 深刻度
     * @return レベル文字列
     */
    private String determineSeverityLevel(String attackType, String severity) {
        if (attackType == null) {
            return "low";
        }

        // 高リスク攻撃タイプ
        if (attackType.contains("SQL_INJECTION") || attackType.contains("COMMAND_INJECTION") || attackType.contains("XXE")) {
            return "high";
        }

        // 中リスク攻撃タイプ
        if (attackType.contains("XSS") || attackType.contains("LFI") || attackType.contains("RFI")) {
            return "medium";
        }

        // ModSecurity severity基準
        if ("CRITICAL".equalsIgnoreCase(severity) || "ERROR".equalsIgnoreCase(severity)) {
            return "high";
        } else if ("WARNING".equalsIgnoreCase(severity)) {
            return "medium";
        }

        return "low";
    }

    /**
     * サーバーステータスを判定
     * @param isActive アクティブフラグ
     * @param lastLogReceived 最終ログ受信時刻
     * @return ステータス文字列
     */
    private String determineServerStatus(boolean isActive, java.sql.Timestamp lastLogReceived) {
        if (!isActive) {
            return "offline";
        }

        if (lastLogReceived == null) {
            return "unknown";
        }

        // 30分以内にログを受信していればオンライン
        long timeDiff = System.currentTimeMillis() - lastLogReceived.getTime();
        if (timeDiff < 30 * 60 * 1000) { // 30分
            return "online";
        } else {
            return "stale"; // データが古い
        }
    }

    /**
     * 攻撃タイプの説明を取得
     * attack_patterns.yamlのキーに対応したAttackPatternの説明取得メソッドを利用
     * @param attackType 攻撃タイプ
     * @return 説明文字列
     */
    private String getAttackTypeDescription(String attackType) {
        // attack_patterns.yamlのパス（環境変数またはデフォルト）
        String yamlPath = System.getenv("ATTACK_PATTERNS_YAML_PATH");
        if (yamlPath == null || yamlPath.isEmpty()) {
            yamlPath = "container/config/attack_patterns.yaml";
        }
        return com.edamame.security.AttackPattern.getAttackTypeDescriptionYaml(attackType, yamlPath);
    }

    /**
     * DB接続が有効かどうかを判定（static移行対応）
     * @return 接続有効ならtrue
     */
    public boolean isConnectionValid() {
        return isConnected();
    }
}
