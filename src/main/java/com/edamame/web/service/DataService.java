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
            // 互換性のために 'totalAttacks' とテンプレートで参照される可能性がある 'attackCount' の両方を設定
            int totalAttacks = getTotalAttacksToday();
            stats.put("totalAttacks", totalAttacks);
            stats.put("attackCount", totalAttacks);

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

            // 攻撃タイプ別統計はダッシュボード表示から除外（不要・集計範囲が他と一致しないため）

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
        // アクティブなサーバーのみ集計する
        String sql = "SELECT COUNT(*) FROM access_log al JOIN servers s ON al.server_name COLLATE utf8mb4_unicode_ci = s.server_name COLLATE utf8mb4_unicode_ci WHERE DATE(al.access_time) = CURDATE() AND s.is_active = TRUE";
        return executeCountQuery(sql);
    }

    /**
     * 今日の攻撃検知数を取得
     * @return 攻撃検知数
     */
    private int getTotalAttacksToday() {
        // 各 access_log を servers と結合し、is_active = TRUE のサーバーのみを対象に攻撃判定を行う
        String sql = """
            SELECT COUNT(DISTINCT al.id)
            FROM access_log al
            JOIN servers s ON al.server_name COLLATE utf8mb4_unicode_ci = s.server_name COLLATE utf8mb4_unicode_ci
            LEFT JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url
            WHERE DATE(al.access_time) = CURDATE()
              AND s.is_active = TRUE
              AND (
                  al.blocked_by_modsec = TRUE
                  OR (ur.attack_type IS NOT NULL AND ur.attack_type NOT IN ('CLEAN', 'UNKNOWN', 'normal'))
              )
            """;
        return executeCountQuery(sql);
    }

    /**
     * 今日のModSecurityブロック数を取得
     * @return ブロック数
     */
    private int getModSecBlocksToday() {
        String sql = "SELECT COUNT(*) FROM access_log al JOIN servers s ON al.server_name COLLATE utf8mb4_unicode_ci = s.server_name COLLATE utf8mb4_unicode_ci WHERE DATE(al.access_time) = CURDATE() AND al.blocked_by_modsec = TRUE AND s.is_active = TRUE";
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
        // alerts テーブルが存在しない環境向けに、modsec_alerts と access_log から必要情報を取得する
        String sql = """
            SELECT s.server_name AS server_name,
                   ma.rule_id AS rule_id,
                   ma.severity AS severity,
                   ma.message AS message,
                   al.ip_address AS source_ip,
                   al.full_url AS target_url,
                   'MODSEC' AS alert_type,
                   FALSE AS is_resolved,
                   ma.created_at AS created_at
            FROM modsec_alerts ma
            LEFT JOIN access_log al ON ma.access_log_id = al.id
            LEFT JOIN servers s ON al.server_name = s.server_name
            ORDER BY ma.created_at DESC
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
        // サーバー管理画面向け: 全サーバーを返す（有効/無効の両方を管理画面で確認できるようにする）
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
                // クライアント側で期待されるキー名に合わせてマップを作成
                server.put("id", rs.getInt("id"));
                server.put("serverName", rs.getString("server_name"));
                server.put("serverDescription", rs.getString("server_description"));
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
        // server_stats テーブルが存在しない場合は servers テーブルとアクセスログを集計して取得する
        String sql = """
            SELECT s.server_name,
                   s.server_description,
                   s.is_active,
                   s.last_log_received,
                   COALESCE(a.total_access, 0) AS total_access,
                   COALESCE(today.total_access_today, 0) AS today_access_count,
                   COALESCE(ar.attack_count, 0) AS attack_count,
                   COALESCE(m.modsec_blocks, 0) AS modsec_blocks
            FROM servers s
            LEFT JOIN (
                SELECT server_name, COUNT(*) AS total_access FROM access_log GROUP BY server_name
            ) a ON a.server_name = s.server_name
            LEFT JOIN (
                SELECT server_name, COUNT(*) AS total_access_today
                FROM access_log
                WHERE DATE(access_time) = CURDATE()
                GROUP BY server_name
            ) today ON today.server_name = s.server_name
            LEFT JOIN (
                SELECT al.server_name, COUNT(DISTINCT al.id) AS attack_count
                FROM access_log al
                LEFT JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url
                WHERE (
                    al.blocked_by_modsec = TRUE
                    OR (ur.attack_type IS NOT NULL AND ur.attack_type NOT IN ('CLEAN', 'UNKNOWN', 'normal'))
                )
                GROUP BY al.server_name
            ) ar ON ar.server_name = s.server_name
            LEFT JOIN (
                SELECT server_name, SUM(CASE WHEN blocked_by_modsec=TRUE THEN 1 ELSE 0 END) AS modsec_blocks
                FROM access_log GROUP BY server_name
            ) m ON m.server_name = s.server_name
            WHERE s.is_active = TRUE
            ORDER BY s.server_name COLLATE utf8mb4_unicode_ci
            """;
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> stat = new HashMap<>();
                stat.put("serverName", rs.getString("server_name"));
                stat.put("serverDescription", rs.getString("server_description"));
                stat.put("isActive", rs.getBoolean("is_active"));
                stat.put("lastLogReceived", formatDateTime(rs.getTimestamp("last_log_received")));
                stat.put("totalAccess", rs.getInt("total_access"));
                // 今日のアクセス数を追加
                stat.put("todayAccessCount", rs.getInt("today_access_count"));
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
        // 今日のアクセスログをベースに、攻撃判定と同一のフィルタで攻撃タイプ別に集計する
        // 範囲: DATE(access_time)=CURDATE(), サーバは active のみを集計対象
        // 攻撃タイプは url_registry.attack_type を優先し、マッピングが無く ModSecurity によるブロックがある場合は 'MODSEC' とする
        // only_full_group_by 回避のため、サブクエリで各アクセスごとに attack_type を決定し
        // 外側で集計（GROUP BY）する方式に変更
        String sql = """
            SELECT attack_type, COUNT(*) AS count FROM (
                SELECT al.id AS log_id,
                       CASE
                           WHEN ur.attack_type IS NOT NULL AND ur.attack_type NOT IN ('CLEAN','UNKNOWN','normal') THEN ur.attack_type
                           WHEN al.blocked_by_modsec = TRUE THEN 'MODSEC'
                           ELSE 'UNKNOWN'
                       END AS attack_type
                FROM access_log al
                JOIN servers s ON al.server_name COLLATE utf8mb4_unicode_ci = s.server_name COLLATE utf8mb4_unicode_ci
                LEFT JOIN url_registry ur ON al.method = ur.method AND al.full_url = ur.full_url
                WHERE DATE(al.access_time) = CURDATE()
                  AND s.is_active = TRUE
                  AND (
                      al.blocked_by_modsec = TRUE
                      OR (ur.attack_type IS NOT NULL AND ur.attack_type NOT IN ('CLEAN','UNKNOWN','normal'))
                  )
            ) sub
            GROUP BY attack_type
            ORDER BY count DESC
            """;

        int sum = 0;
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String typeName = rs.getString("attack_type");
                int cnt = rs.getInt("count");
                Map<String, Object> type = new HashMap<>();
                String finalizedName = typeName == null ? "UNKNOWN" : typeName;
                type.put("type", finalizedName);
                type.put("count", cnt);
                type.put("description", "MODSEC".equals(finalizedName) ? "ModSecurity によるブロック" : getAttackTypeDescription(finalizedName));
                attackTypes.add(type);
                sum += cnt;
            }
        } catch (SQLException e) {
            AppLogger.error("攻撃タイプ統計取得エラー: " + e.getMessage());
        }

        // 総攻撃数と突合し、差分があれば 'UNMAPPED' エントリとして補正
        try {
            int totalAttacksToday = getTotalAttacksToday();
            if (totalAttacksToday > sum) {
                int diff = totalAttacksToday - sum;
                Map<String, Object> other = new HashMap<>();
                other.put("type", "UNMAPPED");
                other.put("count", diff);
                other.put("description", "集計差分（マッピングされていない/ModSecurity以外の検知）");
                attackTypes.add(other);
            }
        } catch (Exception ignored) {}

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

    /**
     * 指定サーバーを無効化する（is_active = FALSE）
     * @param id サーバーID
     * @return 成功したらtrue
     */
    public boolean disableServerById(int id) {
        String sql = "UPDATE servers SET is_active = FALSE, updated_at = NOW() WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            int updated = stmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            AppLogger.error("サーバー無効化エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * 指定サーバーを有効化する（is_active = TRUE）
     * @param id サーバーID
     * @return 成功したらtrue
     */
    public boolean enableServerById(int id) {
        String sql = "UPDATE servers SET is_active = TRUE, updated_at = NOW() WHERE id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setInt(1, id);
            int updated = stmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            AppLogger.error("サーバー有効化エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * URL脅威度一覧を取得（サーバー単位・脅威度フィルタ対応）
     * @param serverName サーバー名（null/空なら全件）
     * @param threatFilter 脅威度フィルタ（all/safe/danger/caution/unknown）
     * @return 脅威度情報リスト
     */
    public List<Map<String, Object>> getUrlThreats(String serverName, String threatFilter, String query) {
        List<Map<String, Object>> results = new ArrayList<>();
        boolean hasServerFilter = serverName != null && !serverName.isBlank();
        StringBuilder sql = new StringBuilder();
        sql.append("\n            SELECT ur.server_name, ur.method, ur.full_url, ur.is_whitelisted, ur.attack_type, ur.user_final_threat, ur.user_threat_note,\n")
           .append("                   ur.latest_blocked_by_modsec AS latest_blocked_by_modsec,\n")
           .append("                   ur.latest_status_code AS latest_status_code,\n")
           .append("                   ur.latest_access_time AS latest_access_time\n")
           .append("            FROM url_registry ur\n");
        if (hasServerFilter) {
            sql.append("            WHERE ur.server_name COLLATE utf8mb4_unicode_ci = ?\n");
        }
        sql.append("            ORDER BY ur.server_name COLLATE utf8mb4_unicode_ci, ur.latest_access_time DESC\n");

        try (Connection conn = getConnection()) {
            if (conn == null) {
                AppLogger.error("URL脅威度取得エラー: DB接続が初期化されていません");
                return results;
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
                if (hasServerFilter) {
                    stmt.setString(1, serverName);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        String sName = rs.getString("server_name");
                        String method = rs.getString("method");
                        String fullUrl = rs.getString("full_url");
                        boolean isWhitelisted = rs.getBoolean("is_whitelisted");
                        String attackType = rs.getString("attack_type");
                        String attackTypeDisplay = "normal".equalsIgnoreCase(attackType) ? "" : attackType;
                        Object userFinalThreatObj = rs.getObject("user_final_threat");
                        Boolean userFinalThreat = userFinalThreatObj == null ? null : rs.getBoolean("user_final_threat");
                        Boolean latestBlocked = (Boolean) rs.getObject("latest_blocked_by_modsec");
                        Integer latestStatus = (Integer) rs.getObject("latest_status_code");
                        java.sql.Timestamp ts = rs.getTimestamp("latest_access_time");
                        String threatNote = rs.getString("user_threat_note");

                        ThreatEvaluation eval = evaluateThreat(isWhitelisted, userFinalThreat, attackType, latestBlocked);

                        row.put("serverName", sName);
                        row.put("method", method);
                        row.put("fullUrl", fullUrl);
                        row.put("attackType", attackTypeDisplay);
                        row.put("isWhitelisted", isWhitelisted);
                        row.put("userFinalThreat", userFinalThreat);
                        row.put("userThreatNote", threatNote);
                        row.put("latestBlockedByModsec", latestBlocked);
                        row.put("latestStatusCode", latestStatus);
                        row.put("latestAccessTime", formatDateTime(ts));
                        row.put("latestAccessEpoch", ts == null ? 0L : ts.getTime());
                        row.put("threatKey", eval.key());
                        row.put("threatLabel", eval.label());
                        row.put("threatPriority", eval.priority());

                        results.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            AppLogger.error("URL脅威度取得エラー: " + e.getMessage());
        }

        String filter = threatFilter == null ? "all" : threatFilter.toLowerCase();
        results.removeIf(m -> {
            String key = String.valueOf(m.getOrDefault("threatKey", "unknown"));
            return !"all".equals(filter) && !filter.equals(key);
        });

        String q = query == null ? "" : query.toLowerCase().trim();
        if (!q.isEmpty()) {
            results.removeIf(m -> {
                String url = String.valueOf(m.getOrDefault("fullUrl", ""));
                String atk = String.valueOf(m.getOrDefault("attackType", ""));
                String method = String.valueOf(m.getOrDefault("method", ""));
                return !(url.toLowerCase().contains(q) || atk.toLowerCase().contains(q) || method.toLowerCase().contains(q));
            });
        }

        results.sort((a, b) -> {
            int pa = toInt(a.get("threatPriority"));
            int pb = toInt(b.get("threatPriority"));
            if (pa != pb) return Integer.compare(pa, pb);
            long ta = (a.get("latestAccessEpoch") instanceof Number n1) ? n1.longValue() : 0L;
            long tb = (b.get("latestAccessEpoch") instanceof Number n2) ? n2.longValue() : 0L;
            return Long.compare(tb, ta);
        });
        return results;
    }

    /**
     * URL脅威度のユーザー分類を更新
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @param action 実行アクション（danger/safe/clear/note）
     * @param note 理由メモ
     * @return 更新成功ならtrue
     */
    public boolean updateUrlThreatCategory(String serverName, String method, String fullUrl, String action, String note) {
        if (serverName == null || method == null || fullUrl == null || action == null) {
            AppLogger.warn("URL脅威度更新パラメータが不足しています");
            return false;
        }
        String normalizedAction = action.trim().toLowerCase();
        String sql;
        switch (normalizedAction) {
            case "danger" -> sql = "UPDATE url_registry SET user_final_threat = TRUE, is_whitelisted = FALSE, user_threat_note = ?, updated_at = NOW() WHERE server_name COLLATE utf8mb4_unicode_ci = ? AND method = ? AND full_url = ?";
            case "safe" -> sql = "UPDATE url_registry SET is_whitelisted = TRUE, user_final_threat = FALSE, user_threat_note = ?, updated_at = NOW() WHERE server_name COLLATE utf8mb4_unicode_ci = ? AND method = ? AND full_url = ?";
            case "clear" -> sql = "UPDATE url_registry SET is_whitelisted = FALSE, user_final_threat = FALSE, user_threat_note = ?, updated_at = NOW() WHERE server_name COLLATE utf8mb4_unicode_ci = ? AND method = ? AND full_url = ?";
            case "note" -> sql = "UPDATE url_registry SET user_threat_note = ?, updated_at = NOW() WHERE server_name COLLATE utf8mb4_unicode_ci = ? AND method = ? AND full_url = ?";
            default -> {
                AppLogger.warn("未対応のURL脅威度更新アクション: " + normalizedAction);
                return false;
            }
        }

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, note == null ? "" : note);
            stmt.setString(2, serverName);
            stmt.setString(3, method);
            stmt.setString(4, fullUrl);
            int updated = stmt.executeUpdate();
            if (updated == 0) {
                // サーバー名不一致で更新できない場合にフォールバック（method + full_urlで最新を1件更新）
                String fallbackSql;
                switch (normalizedAction) {
                    case "danger" -> fallbackSql = "UPDATE url_registry SET user_final_threat = TRUE, is_whitelisted = FALSE, user_threat_note = ?, updated_at = NOW() WHERE method = ? AND full_url = ? LIMIT 1";
                    case "safe" -> fallbackSql = "UPDATE url_registry SET is_whitelisted = TRUE, user_final_threat = FALSE, user_threat_note = ?, updated_at = NOW() WHERE method = ? AND full_url = ? LIMIT 1";
                    case "clear" -> fallbackSql = "UPDATE url_registry SET is_whitelisted = FALSE, user_final_threat = FALSE, user_threat_note = ?, updated_at = NOW() WHERE method = ? AND full_url = ? LIMIT 1";
                    default -> fallbackSql = "UPDATE url_registry SET user_threat_note = ?, updated_at = NOW() WHERE method = ? AND full_url = ? LIMIT 1";
                }
                try (PreparedStatement fb = conn.prepareStatement(fallbackSql)) {
                    fb.setString(1, note == null ? "" : note);
                    fb.setString(2, method);
                    fb.setString(3, fullUrl);
                    int fbUpdated = fb.executeUpdate();
                    if (fbUpdated > 0) {
                        AppLogger.info("URL脅威度を更新しました(フォールバック): action=" + normalizedAction + " method=" + method + " url=" + fullUrl);
                        return true;
                    }
                }
            } else {
                AppLogger.info("URL脅威度を更新しました: action=" + normalizedAction + " server=" + serverName + " method=" + method + " url=" + fullUrl);
                return true;
            }
            AppLogger.warn("URL脅威度更新対象が見つかりません: " + method + " " + fullUrl + " (server=" + serverName + ")");
        } catch (SQLException e) {
            AppLogger.error("URL脅威度更新エラー: " + e.getMessage());
        }
        return false;
    }

    private record ThreatEvaluation(String key, String label, int priority) {}

    /**
     * 脅威度を判定する
     * @param isWhitelisted ホワイトリスト判定
     * @param userFinalThreat ユーザー最終判定
     * @param attackType 攻撃タイプ
     * @param latestBlocked 最新アクセスのModSecブロック有無
     * @return 判定結果
     */
    private ThreatEvaluation evaluateThreat(boolean isWhitelisted, Boolean userFinalThreat, String attackType, Boolean latestBlocked) {
        if (Boolean.TRUE.equals(userFinalThreat)) {
            return new ThreatEvaluation("danger", "危険", 1);
        }
        if (isWhitelisted) {
            return new ThreatEvaluation("safe", "安全", 4);
        }
        // ModSecブロックが直近にある場合は注意
        if (Boolean.TRUE.equals(latestBlocked)) {
            return new ThreatEvaluation("caution", "注意", 2);
        }
        // attackType が normal 以外なら注意、それ以外は不明
        String atk = attackType == null ? "" : attackType.trim();
        if (!atk.isEmpty() && !"normal".equalsIgnoreCase(atk)) {
            return new ThreatEvaluation("caution", "注意", 2);
        }
        if ("normal".equalsIgnoreCase(atk)) {
            return new ThreatEvaluation("unknown", "不明", 3);
        }
        return new ThreatEvaluation("unknown", "不明", 3);
    }

    private int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}


