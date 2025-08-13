package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import java.sql.*;
import java.util.Map;

/**
 * サーバー情報の登録・更新専用ユーティリティ
 * registerOrUpdateServer, insertAccessLog, registerUrlRegistryEntry等を提供
 * v2.0.0: Connection引数を完全廃止、DbService専用に統一
 */
public class DbRegistry {

    /**
     * サーバー情報を登録または更新（DbService使用）
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void registerOrUpdateServer(DbService dbService, String serverName, String description, String logPath) throws SQLException {
        // サーバー名をサニタイズ
        if (serverName == null || serverName.trim().isEmpty()) {
            serverName = "default";
        }

        final String finalServerName = serverName;

        dbService.getSession().execute(conn -> {
            try {
                // サーバー情報の存在確認
                String checkSql = "SELECT COUNT(*) FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci";
                boolean serverExists = false;
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, finalServerName);
                    try (ResultSet rs = checkStmt.executeQuery()) {
                        if (rs.next()) {
                            serverExists = rs.getInt(1) > 0;
                        }
                    }
                }

                if (serverExists) {
                    // サーバーが存在する場合は更新
                    DbUpdate.updateServerInfo(dbService, finalServerName, description, logPath);
                } else {
                    // サーバーが存在しない場合は新規登録
                    String insertSql = """
                        INSERT INTO servers (server_name, server_description, log_path, is_active, last_log_received)
                        VALUES (?, ?, ?, TRUE, NOW())
                        """;

                    try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                        insertStmt.setString(1, finalServerName);
                        insertStmt.setString(2, description != null ? description : "");
                        insertStmt.setString(3, logPath != null ? logPath : "");

                        int inserted = insertStmt.executeUpdate();
                        if (inserted > 0) {
                            AppLogger.log("新規サーバーを登録しました: " + finalServerName, "INFO");
                        }
                    }
                }
            } catch (SQLException e) {
                AppLogger.log("サーバー登録・更新エラー: " + e.getMessage(), "ERROR");
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * エージェントサーバーを登録または更新（DbService使用）
     * @param dbService データベースサービス
     * @param serverInfo サーバー情報Map
     * @return 登録ID（成功時）、null（失敗時）
     * @throws SQLException SQL例外
     */
    public static String registerOrUpdateAgent(DbService dbService, Map<String, Object> serverInfo) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                // 登録IDを生成
                String registrationId = generateAgentRegistrationId();

                String sql = """
                    INSERT INTO agent_servers (
                        registration_id, agent_name, agent_ip,
                        hostname, os_name, os_version, java_version, nginx_log_paths,
                        iptables_enabled, registered_at, last_heartbeat, status, agent_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 'active', ?)
                    ON DUPLICATE KEY UPDATE
                        agent_ip = VALUES(agent_ip),
                        hostname = VALUES(hostname),
                        os_name = VALUES(os_name),
                        os_version = VALUES(os_version),
                        java_version = VALUES(java_version),
                        nginx_log_paths = VALUES(nginx_log_paths),
                        iptables_enabled = VALUES(iptables_enabled),
                        last_heartbeat = NOW(),
                        status = 'active',
                        agent_version = VALUES(agent_version)
                    """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    // ObjectMapperを使用してJSON文字列に変換
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

                    pstmt.setString(1, registrationId);
                    pstmt.setString(2, (String) serverInfo.get("agentName"));
                    pstmt.setString(3, (String) serverInfo.get("agentIp"));
                    pstmt.setString(4, (String) serverInfo.get("hostname"));
                    pstmt.setString(5, (String) serverInfo.get("osName"));
                    pstmt.setString(6, (String) serverInfo.get("osVersion"));
                    pstmt.setString(7, (String) serverInfo.get("javaVersion"));
                    pstmt.setString(8, objectMapper.writeValueAsString(serverInfo.get("nginxLogPaths")));
                    pstmt.setBoolean(9, (Boolean) serverInfo.getOrDefault("iptablesEnabled", true));
                    pstmt.setString(10, (String) serverInfo.getOrDefault("agentVersion", "unknown"));

                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.log("エージェント登録成功: " + serverInfo.get("agentName") + " (ID: " + registrationId + ")", "INFO");
                        return registrationId;
                    } else {
                        AppLogger.log("エージェント登録失敗: " + serverInfo.get("agentName"), "ERROR");
                        return null;
                    }
                }

            } catch (Exception e) {
                AppLogger.log("エージェント登録でエラー: " + e.getMessage(), "ERROR");
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * access_logテーブルにログを保存する（DbService使用）
     * @param dbService データベースサービス
     * @param parsedLog ログ情報Map
     * @return 登録されたaccess_logのID（Long）、失敗時はnull
     * @throws SQLException SQL例外
     */
    public static Long insertAccessLog(DbService dbService, Map<String, Object> parsedLog) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = """
                    INSERT INTO access_log (
                        method, full_url, status_code, ip_address,
                        access_time, blocked_by_modsec, server_name,
                        source_path, collected_at, agent_registration_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
                try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    pstmt.setString(1, (String) parsedLog.get("method"));
                    pstmt.setString(2, (String) parsedLog.get("full_url"));
                    pstmt.setInt(3, (Integer) parsedLog.get("status_code"));
                    pstmt.setString(4, (String) parsedLog.get("ip_address"));
                    Object accessTime = parsedLog.get("access_time");
                    if (accessTime instanceof java.time.LocalDateTime) {
                        pstmt.setObject(5, accessTime);
                    } else {
                        pstmt.setObject(5, java.time.LocalDateTime.now());
                    }
                    pstmt.setBoolean(6, (Boolean) parsedLog.getOrDefault("blocked_by_modsec", false));
                    pstmt.setString(7, (String) parsedLog.get("server_name"));
                    pstmt.setString(8, (String) parsedLog.get("source_path"));
                    pstmt.setString(9, (String) parsedLog.get("collected_at"));
                    pstmt.setString(10, (String) parsedLog.get("agent_registration_id"));
                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        ResultSet rs = pstmt.getGeneratedKeys();
                        if (rs.next()) {
                            return rs.getLong(1);
                        }
                    }
                }
                return null;
            } catch (SQLException e) {
                AppLogger.log("access_log登録エラー: " + e.getMessage(), "ERROR");
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * url_registryテーブルに新規URLを登録（DbService使用）
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @param isWhitelisted ホワイトリスト判定
     * @param attackType 攻撃タイプ
     * @return 登録成功時はtrue、失敗時はfalse
     * @throws SQLException SQL例外
     */
    public static boolean registerUrlRegistryEntry(DbService dbService, String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String insertSql = """
                    INSERT INTO url_registry (server_name, method, full_url, created_at, updated_at, is_whitelisted, attack_type)
                    VALUES (?, ?, ?, NOW(), NOW(), ?, ?)
                    """;
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, serverName);
                    insertStmt.setString(2, method);
                    insertStmt.setString(3, fullUrl);
                    insertStmt.setBoolean(4, isWhitelisted);
                    insertStmt.setString(5, attackType);
                    int affected = insertStmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.log("新規URLを登録: " + serverName + " - " + method + " " + fullUrl + " (攻撃タイプ: " + attackType + ")", "INFO");
                        return true;
                    } else {
                        AppLogger.log("URL登録失敗: " + serverName + " - " + method + " " + fullUrl, "ERROR");
                        return false;
                    }
                }
            } catch (SQLException e) {
                AppLogger.log("URL登録エラー: " + e.getMessage(), "ERROR");
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * modsec_alertsテーブルにModSecurityアラートを保存（DbService使用）
     * @param dbService データベースサービス
     * @param accessLogId 関連するアクセスログID
     * @param modSecInfo ModSecurity情報Map
     * @throws SQLException SQL例外
     */
    public static void insertModSecAlert(DbService dbService, Long accessLogId, Map<String, Object> modSecInfo) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = """
                    INSERT INTO modsec_alerts (
                        access_log_id, rule_id, severity, message, data_value,
                        created_at, detected_at, server_name
                    ) VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?)
                    """;
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setLong(1, accessLogId);
                    pstmt.setString(2, (String) modSecInfo.getOrDefault("rule_id", "unknown"));
                    
                    // severityの型安全な処理
                    Object severityObj = modSecInfo.get("severity");
                    if (severityObj instanceof Integer) {
                        pstmt.setInt(3, (Integer) severityObj);
                    } else if (severityObj instanceof String) {
                        try {
                            pstmt.setInt(3, Integer.parseInt((String) severityObj));
                        } catch (NumberFormatException e) {
                            pstmt.setInt(3, 2); // デフォルト値（critical）
                        }
                    } else {
                        pstmt.setInt(3, 2); // デフォルト値（critical）
                    }
                    
                    pstmt.setString(4, (String) modSecInfo.getOrDefault("message", "ModSecurity Access Denied"));
                    pstmt.setString(5, (String) modSecInfo.getOrDefault("data_value", ""));
                    pstmt.setString(6, (String) modSecInfo.getOrDefault("server_name", "unknown"));
                    
                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.debug("ModSecurity alert saved successfully for access_log ID: " + accessLogId);
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("Error saving ModSec alert: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * エージェント登録IDを生成
     * @return 生成された登録ID
     */
    private static String generateAgentRegistrationId() {
        return "agent-" + System.currentTimeMillis() + "-" +
               String.format("%04x", new java.security.SecureRandom().nextInt(0x10000));
    }
}
