package com.edamame.security.db;

import java.sql.*;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * サーバー情報の登録・更新専用ユーティリティ
 * registerOrUpdateServer, updateServerLastLogReceivedを提供
 */
public class DbRegistry {
    /**
     * サーバー情報を登録または更新（照合順序対応版）
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @param logFunc ログ出力関数
     */
    public static void registerOrUpdateServer(Connection conn, String serverName, String description, String logPath, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        // サーバー名をサニタイズ
        if (serverName == null || serverName.trim().isEmpty()) {
            serverName = "default";
        }

        try {
            // サーバー情報SELECTをDbSelectに委譲
            if (DbSelect.selectServerInfoByName(conn, serverName).isPresent()) {
                // サーバーが存在する場合は更新
                DbUpdate.updateServerInfo(conn, serverName, description, logPath, logFunc);
            } else {
                // サーバーが存在しない場合は新規登録
                String insertSql = """
                    INSERT INTO servers (server_name, server_description, log_path, is_active, last_log_received)
                    VALUES (?, ?, ?, TRUE, NOW())
                    """;

                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, serverName);
                    insertStmt.setString(2, description != null ? description : "");
                    insertStmt.setString(3, logPath != null ? logPath : "");

                    int inserted = insertStmt.executeUpdate();
                    if (inserted > 0) {
                        log.accept("新規サーバーを登録しました: " + serverName, "INFO");
                    }
                }
            }
        } catch (SQLException e) {
            log.accept("サーバー登録・更新エラー: " + e.getMessage(), "ERROR");
        }
    }



    /**
     * エージェントサーバーを登録または更新
     * TCP接続時のサーバー登録処理
     * 
     * @param conn データベース接続
     * @param serverInfo サーバー情報Map
     * @param logFunc ログ出力関数
     * @return 登録ID（成功時）、null（失敗時）
     */
    public static String registerOrUpdateAgent(Connection conn, Map<String, Object> serverInfo, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

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
                // ObjectMapperを��用してJSON文字列に変換
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
                    log.accept("エージェント登録成功: " + serverInfo.get("agentName") + " (ID: " + registrationId + ")", "INFO");
                    return registrationId;
                } else {
                    log.accept("エージェント登録失敗: " + serverInfo.get("agentName"), "ERROR");
                    return null;
                }
            }

        } catch (Exception e) {
            log.accept("エージェント登録でエラー: " + e.getMessage(), "ERROR");
            return null;
        }
    }



    /**
     * エージェント登録IDを生成
     * 「agent-{timestamp}-{random}」形式で生成
     * 
     * @return 生成された登録ID
     */
    private static String generateAgentRegistrationId() {
        return "agent-" + System.currentTimeMillis() + "-" +
               String.format("%04x", new java.security.SecureRandom().nextInt(0x10000));
    }
}
