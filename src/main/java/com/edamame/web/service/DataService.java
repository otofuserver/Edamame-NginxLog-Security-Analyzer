package com.edamame.web.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * データサービスクラス
 * データベースからの情報取得とWebフロントエンド向けデータ処理を担当
 */
public class DataService {

    private final Connection dbConnection;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param dbConnection データベース接続
     * @param logFunction ログ出力関数
     */
    public DataService(Connection dbConnection, BiConsumer<String, String> logFunction) {
        this.dbConnection = dbConnection;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
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

            // アクティブサーバー数
            stats.put("activeServers", getActiveServerCount());

            // 最新アラート
            stats.put("recentAlerts", getRecentAlerts(10));

            // サーバー一覧
            stats.put("serverList", getServerList());

            // 攻撃タイプ別統計
            stats.put("attackTypes", getAttackTypeStats());

            logFunction.accept("ダッシュボード統計情報取得完了", "DEBUG");

        } catch (Exception e) {
            logFunction.accept("ダッシュボード統計情報取得エラー: " + e.getMessage(), "ERROR");
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
        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            logFunction.accept("カウントクエリ実行エラー: " + e.getMessage(), "ERROR");
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
            SELECT al.server_name, al.access_time, al.ip_address, al.full_url, 
                   ur.attack_type, ma.rule_id, ma.severity
            FROM access_log al
            LEFT JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url AND al.server_name = ur.server_name
            LEFT JOIN modsec_alerts ma ON al.id = ma.access_log_id
            WHERE (al.blocked_by_modsec = TRUE OR ur.attack_type NOT IN ('CLEAN', 'UNKNOWN', 'normal'))
            ORDER BY al.access_time DESC
            LIMIT ?
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setInt(1, limit);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                Map<String, Object> alert = new HashMap<>();
                alert.put("serverName", rs.getString("server_name"));
                alert.put("accessTime", formatDateTime(rs.getTimestamp("access_time")));
                alert.put("ipAddress", rs.getString("ip_address"));
                alert.put("url", rs.getString("full_url"));
                alert.put("attackType", rs.getString("attack_type"));
                alert.put("ruleId", rs.getString("rule_id"));
                alert.put("severity", rs.getString("severity"));
                alert.put("severityLevel", determineSeverityLevel(rs.getString("attack_type"), rs.getString("severity")));

                alerts.add(alert);
            }

        } catch (SQLException e) {
            logFunction.accept("最新アラート取得エラー: " + e.getMessage(), "ERROR");
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
            SELECT s.server_name, s.server_description, s.is_active, s.last_log_received,
                   COUNT(al.id) as today_access_count
            FROM servers s
            LEFT JOIN access_log al ON s.server_name COLLATE utf8mb4_unicode_ci = al.server_name COLLATE utf8mb4_unicode_ci 
                AND DATE(al.access_time) = CURDATE()
            GROUP BY s.id, s.server_name, s.server_description, s.is_active, s.last_log_received
            ORDER BY s.server_name COLLATE utf8mb4_unicode_ci
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();

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

            logFunction.accept("サーバー一覧取得完了（照合順序対応版）: " + servers.size() + "台", "DEBUG");

        } catch (SQLException e) {
            logFunction.accept("サーバー一覧取得エラー（照合順序修正版）: " + e.getMessage(), "ERROR");
        }

        return servers;
    }

    /**
     * 攻撃タイプ別統計を取得
     * @return 攻撃タイプ統計のリスト
     */
    public List<Map<String, Object>> getAttackTypeStats() {
        List<Map<String, Object>> attackTypes = new ArrayList<>();

        String sql = """
            SELECT ur.attack_type, COUNT(al.id) as count
            FROM access_log al
            JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url AND al.server_name = ur.server_name
            WHERE DATE(al.access_time) = CURDATE()
            AND ur.attack_type NOT IN ('CLEAN', 'UNKNOWN', 'normal')
            GROUP BY ur.attack_type
            ORDER BY count DESC
            LIMIT 10
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                Map<String, Object> attackType = new HashMap<>();
                attackType.put("type", rs.getString("attack_type"));
                attackType.put("count", rs.getInt("count"));
                attackType.put("description", getAttackTypeDescription(rs.getString("attack_type")));

                attackTypes.add(attackType);
            }

        } catch (SQLException e) {
            logFunction.accept("攻撃タイプ統計取得エラー: " + e.getMessage(), "ERROR");
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
            logFunction.accept("API統計データ取得エラー: " + e.getMessage(), "ERROR");
            apiStats.put("error", e.getMessage());
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
     * @param attackType 攻撃タイプ
     * @return 説明文字列
     */
    private String getAttackTypeDescription(String attackType) {
        return switch (attackType) {
            case "SQL_INJECTION" -> "SQLインジェクション攻撃";
            case "XSS" -> "クロスサイトスクリプティング攻撃";
            case "COMMAND_INJECTION" -> "コマンドインジェクション攻撃";
            case "LFI" -> "ローカルファイルインクルード攻撃";
            case "RFI" -> "リモートファイルインクルード攻撃";
            case "XXE" -> "XML外部エンティティ攻撃";
            case "DIRECTORY_TRAVERSAL" -> "ディレクトリトラバーサル攻撃";
            case "CSRF" -> "クロスサイトリクエストフォージェリ攻撃";
            default -> "不明な攻撃タイプ";
        };
    }

    /**
     * データベース接続状態を確認
     * @return 接続可能ならtrue
     */
    public boolean isConnectionValid() {
        try {
            return dbConnection != null && !dbConnection.isClosed() && dbConnection.isValid(5);
        } catch (SQLException e) {
            logFunction.accept("DB接続確認エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }
}
