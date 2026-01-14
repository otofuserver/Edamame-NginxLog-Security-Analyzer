package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.edamame.security.db.DbService.addDefaultRoleHierarchy;

/**
 * サーバー情報の登録・更新専用ユーティリティ
 * registerOrUpdateServer, insertAccessLog, registerUrlRegistryEntry等を提供
 * v2.1.0: DbService static化に対応、DbSessionを直接受け取る設計に変更
 */
public class DbRegistry {

    /**
     * サーバー情報を登録または更新
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void registerOrUpdateServer(DbSession dbSession, String serverName, String description, String logPath) throws SQLException {
        // サーバー名をサニタイズ
        if (serverName == null || serverName.trim().isEmpty()) {
            serverName = "default";
        }

        final String finalServerName = serverName;

        dbSession.execute(conn -> {
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
                    DbUpdate.updateServerInfo(dbSession, finalServerName, description, logPath);
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
                            // 新規登録時のみサーバー固有ロールを作成（同一コネクション内で実行）
                            try {
                                addDefaultRolesForServerInternal(conn, finalServerName);
                            } catch (Exception e) {
                                AppLogger.warn("新規サーバー登録後のロール自動追加エラー: " + finalServerName + " - " + e.getMessage());
                            }
                            // 基本ロール(admin/operator/viewer)の下位にサーバー固有ロールを登録
                            try {
                                DbUpdate.addDefaultRoleHierarchy(dbSession, finalServerName);
                            } catch (Exception e) {
                                AppLogger.warn("基本ロールへの階層付与に失敗: " + finalServerName + " - " + e.getMessage());
                            }
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
     * 内部ユーティリティ: 同一Connection上でサーバー固有のadmin/operator/viewerロールを追加する
     * registerOrUpdateServer 内で新規挿入直後に呼び出すことを想定
     */
    private static void addDefaultRolesForServerInternal(Connection conn, String serverName) throws SQLException {
        if (serverName == null || serverName.trim().isEmpty()) {
            AppLogger.warn("ロール追加時のサーバー名がnull/空です");
            return;
        }
        String[] roles = {"admin", "operator", "viewer"};
        for (String role : roles) {
            String roleName = serverName + "_" + role;
            String description = serverName + "限定" + role;
            String sql = "INSERT IGNORE INTO roles (role_name, description, inherited_roles, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, roleName);
                pstmt.setString(2, description);
                pstmt.setString(3, "[]");
                int affected = pstmt.executeUpdate();
                if (affected > 0) {
                    AppLogger.info("ロール追加: " + roleName);
                }
            } catch (SQLException e) {
                AppLogger.error("ロール追加エラー: " + roleName + " - " + e.getMessage());
            }
        }

        // 追加したロールをデフォルトロールの下位ロールとして登録（同一コネクション上でマージ処理）
        try {
            // operator <- viewer
            Integer adminId = null, operatorId = null, viewerId = null;
            String idSql = "SELECT id FROM roles WHERE role_name = ?";
            try (PreparedStatement ps = conn.prepareStatement(idSql)) {
                ps.setString(1, serverName + "_admin");
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) adminId = rs.getInt(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement(idSql)) {
                ps.setString(1, serverName + "_operator");
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) operatorId = rs.getInt(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement(idSql)) {
                ps.setString(1, serverName + "_viewer");
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) viewerId = rs.getInt(1); }
            }

            // operator inherits viewer
            if (operatorId != null && viewerId != null) {
                String sel = "SELECT inherited_roles FROM roles WHERE role_name = ?";
                String upd = "UPDATE roles SET inherited_roles = ? , updated_at = NOW() WHERE role_name = ?";
                try (PreparedStatement selSt = conn.prepareStatement(sel)) {
                    selSt.setString(1, serverName + "_operator");
                    try (ResultSet rs = selSt.executeQuery()) {
                        java.util.List<Integer> list = new java.util.ArrayList<>();
                        if (rs.next()) {
                            String inheritedJson = rs.getString(1);
                            if (inheritedJson != null && !inheritedJson.isBlank() && !inheritedJson.equals("[]")) {
                                @SuppressWarnings("unchecked")
                                java.util.List<Integer> parsed = (java.util.List<Integer>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(inheritedJson, java.util.List.class);
                                list.addAll(parsed);
                            }
                        }
                        if (!list.contains(viewerId)) {
                            list.add(viewerId);
                            String newJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
                            try (PreparedStatement updSt = conn.prepareStatement(upd)) {
                                updSt.setString(1, newJson);
                                updSt.setString(2, serverName + "_operator");
                                updSt.executeUpdate();
                                AppLogger.info("ロール階層(inherited_roles)追加: " + serverName + "_operator -> id=" + viewerId);
                            }
                        }
                    }
                }
            }

            // admin inherits operator and viewer
            if (adminId != null) {
                String sel = "SELECT inherited_roles FROM roles WHERE role_name = ?";
                String upd = "UPDATE roles SET inherited_roles = ? , updated_at = NOW() WHERE role_name = ?";
                try (PreparedStatement selSt = conn.prepareStatement(sel)) {
                    selSt.setString(1, serverName + "_admin");
                    try (ResultSet rs = selSt.executeQuery()) {
                        java.util.List<Integer> list = new java.util.ArrayList<>();
                        if (rs.next()) {
                            String inheritedJson = rs.getString(1);
                            if (inheritedJson != null && !inheritedJson.isBlank() && !inheritedJson.equals("[]")) {
                                @SuppressWarnings("unchecked")
                                java.util.List<Integer> parsed = (java.util.List<Integer>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(inheritedJson, java.util.List.class);
                                list.addAll(parsed);
                            }
                        }
                        boolean updated = false;
                        if (operatorId != null && !list.contains(operatorId)) { list.add(operatorId); updated = true; }
                        if (viewerId != null && !list.contains(viewerId)) { list.add(viewerId); updated = true; }
                        if (updated) {
                            String newJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
                            try (PreparedStatement updSt = conn.prepareStatement(upd)) {
                                updSt.setString(1, newJson);
                                updSt.setString(2, serverName + "_admin");
                                updSt.executeUpdate();
                                AppLogger.info("ロール階層(inherited_roles)追加: " + serverName + "_admin -> operator/viewer ids added");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.error("サーバー固有ロール間の継承追加エラー(内部): " + e.getMessage());
        }
    }

    /**
     * エージェントサーバーを登録または更新
     * @param dbSession データベースセッション
     * @param serverInfo サーバー情報Map
     * @return 登録ID（成功時）、null（失敗時）
     * @throws SQLException SQL例外
     */
    public static String registerOrUpdateAgent(DbSession dbSession, Map<String, Object> serverInfo) throws SQLException {
        return dbSession.executeWithResult(conn -> {
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
                    pstmt.setString(1, registrationId);
                    pstmt.setString(2, (String) serverInfo.get("agentName"));
                    pstmt.setString(3, (String) serverInfo.get("agentIp"));
                    pstmt.setString(4, (String) serverInfo.get("hostname"));
                    pstmt.setString(5, (String) serverInfo.get("osName"));
                    pstmt.setString(6, (String) serverInfo.get("osVersion"));
                    pstmt.setString(7, (String) serverInfo.get("javaVersion"));

                    // nginxLogPathsを適切に処理（ArrayList or Stringに対応）
                    Object nginxLogPathsObj = serverInfo.get("nginxLogPaths");
                    String nginxLogPathsStr;
                    if (nginxLogPathsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> pathsList = (List<String>) nginxLogPathsObj;
                        try {
                            nginxLogPathsStr = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(pathsList);
                        } catch (Exception e) {
                            // JSON化に失敗した場合はカンマ区切り文字列として処理
                            nginxLogPathsStr = String.join(",", pathsList);
                        }
                    } else if (nginxLogPathsObj instanceof String) {
                        nginxLogPathsStr = (String) nginxLogPathsObj;
                    } else {
                        nginxLogPathsStr = nginxLogPathsObj != null ? nginxLogPathsObj.toString() : "";
                    }
                    pstmt.setString(8, nginxLogPathsStr);

                    pstmt.setBoolean(9, (Boolean) serverInfo.getOrDefault("iptablesEnabled", false));
                    pstmt.setString(10, (String) serverInfo.get("agentVersion"));

                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.info("エージェントサーバー登録成功: " + registrationId);
                        return registrationId;
                    }
                }
                return null;
            } catch (SQLException e) {
                AppLogger.error("エージェントサーバー登録エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * access_logテーブルにログを保存
     * @param dbSession データベースセッション
     * @param parsedLog ログ情報Map
     * @return 登録されたaccess_logのID、失敗時はnull
     * @throws SQLException SQL例外
     */
    public static Long insertAccessLog(DbSession dbSession, Map<String, Object> parsedLog) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    INSERT INTO access_log (
                        server_name, ip_address, method, full_url, status_code,
                        access_time, blocked_by_modsec, created_at, source_path, collected_at,
                        agent_registration_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), ?, ?, ?)
                    """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    // サーバー名の処理（NULL安全処理を追加）
                    String serverName = (String) parsedLog.get("server_name");
                    if (serverName == null || serverName.trim().isEmpty()) {
                        // フォールバック: serverNameフィールドも確認
                        serverName = (String) parsedLog.get("serverName");
                        if (serverName == null || serverName.trim().isEmpty()) {
                            serverName = "default"; // デフォルト値を設定
                            AppLogger.warn("server_name/serverNameがnullのため、デフォルト値を設定: " + parsedLog);
                        }
                    }
                    pstmt.setString(1, serverName);
                    
                    // IPアドレスの処理
                    String clientIp = (String) parsedLog.get("ip_address");
                    if (clientIp == null || clientIp.trim().isEmpty()) {
                        // フォールバック: clientIpフィールドも確認
                        clientIp = (String) parsedLog.get("clientIp");
                        if (clientIp == null || clientIp.trim().isEmpty()) {
                            clientIp = "unknown";
                            AppLogger.warn("ip_address/clientIpがnullのため、デフォルト値を設定");
                        }
                    }
                    pstmt.setString(2, clientIp);
                    
                    // HTTPメソッドの処理
                    String httpMethod = (String) parsedLog.get("method");
                    if (httpMethod == null || httpMethod.trim().isEmpty()) {
                        // フォールバック: httpMethodフィールドも確認
                        httpMethod = (String) parsedLog.get("httpMethod");
                        if (httpMethod == null || httpMethod.trim().isEmpty()) {
                            httpMethod = "GET";
                            AppLogger.warn("method/httpMethodがnullのため、デフォルト値を設定");
                        }
                    }
                    pstmt.setString(3, httpMethod);
                    
                    // URLの処理
                    String requestUrl = (String) parsedLog.get("full_url");
                    if (requestUrl == null || requestUrl.trim().isEmpty()) {
                        // フォールバック: requestUrlフィールドも確認
                        requestUrl = (String) parsedLog.get("requestUrl");
                        if (requestUrl == null || requestUrl.trim().isEmpty()) {
                            requestUrl = "/";
                            AppLogger.warn("full_url/requestUrlがnullのため、デフォルト値を設定");
                        }
                    }
                    pstmt.setString(4, requestUrl);
                    
                    // ステータスコードの処理
                    Integer statusCode = (Integer) parsedLog.get("status_code");
                    if (statusCode == null) {
                        // フォールバック: statusCodeフィールドも確認
                        statusCode = (Integer) parsedLog.getOrDefault("statusCode", 0);
                    }
                    pstmt.setInt(5, statusCode);

                    // access_timeの処理（エージェントから送信されたcollectedAtを使用）
                    Object accessTime = parsedLog.get("collectedAt");
                    if (accessTime != null) {
                        // LocalDateTimeまたはTimestamp文字列として処理
                        if (accessTime instanceof LocalDateTime) {
                            pstmt.setTimestamp(6, Timestamp.valueOf((LocalDateTime) accessTime));
                        } else if (accessTime instanceof String) {
                            try {
                                LocalDateTime dateTime = LocalDateTime.parse((String) accessTime);
                                pstmt.setTimestamp(6, Timestamp.valueOf(dateTime));
                            } catch (Exception e) {
                                pstmt.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                            }
                        } else {
                            pstmt.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                        }
                    } else {
                        pstmt.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
                    }

                    pstmt.setBoolean(7, (Boolean) parsedLog.getOrDefault("blockedByModSec", false));
                    
                    // source_pathの処理（snake_caseとcamelCaseの両方に対応）
                    String sourcePath = (String) parsedLog.get("source_path");
                    if (sourcePath == null || sourcePath.trim().isEmpty()) {
                        // フォールバック: sourcePathフィールドも確認
                        sourcePath = (String) parsedLog.get("sourcePath");
                        if (sourcePath == null || sourcePath.trim().isEmpty()) {
                            sourcePath = ""; // デフォルト値（空文字）
                            AppLogger.debug("source_path/sourcePathがnullのため、デフォルト値を設定");
                        }
                    }
                    pstmt.setString(8, sourcePath);

                    // collected_atの処理（instanceof演算子はnullチェックを含むため冗長なnullチェックを削除）
                    Object collectedAt = parsedLog.get("collectedAt");
                    if (collectedAt instanceof LocalDateTime) {
                        pstmt.setTimestamp(9, Timestamp.valueOf((LocalDateTime) collectedAt));
                    } else {
                        pstmt.setTimestamp(9, new Timestamp(System.currentTimeMillis()));
                    }

                    // agent_registration_idの処理（snake_caseとcamelCaseの両方に対応）
                    String agentRegistrationId = (String) parsedLog.get("agent_registration_id");
                    if (agentRegistrationId == null || agentRegistrationId.trim().isEmpty()) {
                        // フォールバック: agentRegistrationIdフィールドも確認
                        agentRegistrationId = (String) parsedLog.get("agentRegistrationId");
                        if (agentRegistrationId == null || agentRegistrationId.trim().isEmpty()) {
                            agentRegistrationId = null; // NULLを許可
                            AppLogger.debug("agent_registration_id/agentRegistrationIdがnullのため、NULLを設定");
                        }
                    }
                    pstmt.setString(10, agentRegistrationId);

                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                long id = generatedKeys.getLong(1); // プリミティブ型を使用
                                AppLogger.debug("アクセスログ保存成功: ID=" + id);
                                return id;
                            }
                        }
                    }
                }
                return null;
            } catch (SQLException e) {
                AppLogger.error("アクセスログ保存エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * url_registryテーブルに新規URLを登録
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @param isWhitelisted ホワイトリスト判定
     * @param attackType 攻撃タイプ
     * @return 登録成功時はtrue
     * @throws SQLException SQL例外
     */
    public static boolean registerUrlRegistryEntry(DbSession dbSession, String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    INSERT INTO url_registry (server_name, method, full_url, is_whitelisted, attack_type, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, NOW(), NOW())
                    """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    pstmt.setString(2, method);
                    pstmt.setString(3, fullUrl);
                    pstmt.setBoolean(4, isWhitelisted);
                    pstmt.setString(5, attackType);

                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.info("URL登録成功: " + method + " " + fullUrl + " (whitelisted=" + isWhitelisted + ")");
                        return true;
                    }
                }
                return false;
            } catch (SQLException e) {
                AppLogger.error("URL登録エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * modsec_alertsテーブルにModSecurityアラートを保存
     * @param dbSession データベースセッション
     * @param accessLogId access_logテーブルのID
     * @param modSecInfo ModSecurity情報Map
     * @throws SQLException SQL例外
     */
    public static void insertModSecAlert(DbSession dbSession, Long accessLogId, Map<String, Object> modSecInfo) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = """
                    INSERT INTO modsec_alerts (
                        access_log_id, rule_id, message, data_value, severity,
                        server_name, detected_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setLong(1, accessLogId);
                    pstmt.setString(2, (String) modSecInfo.get("rule_id"));
                    pstmt.setString(3, (String) modSecInfo.get("message"));
                    pstmt.setString(4, (String) modSecInfo.get("data_value"));

                    // severityはIntegerまたはStringの場合がある
                    Object severity = modSecInfo.get("severity");
                    if (severity instanceof Integer) {
                        pstmt.setInt(5, (Integer) severity);
                    } else if (severity instanceof String) {
                        try {
                            pstmt.setInt(5, Integer.parseInt((String) severity));
                        } catch (NumberFormatException e) {
                            pstmt.setInt(5, 0); // デフォルト値
                        }
                    } else {
                        pstmt.setInt(5, 0);
                    }
                    
                    pstmt.setString(6, (String) modSecInfo.get("server_name"));

                    // detected_atの処理（文字列またはTimestamp/LocalDateTimeに対応）
                    Object detectedAt = modSecInfo.get("detected_at");
                    if (detectedAt != null) {
                        if (detectedAt instanceof LocalDateTime) {
                            pstmt.setTimestamp(7, Timestamp.valueOf((LocalDateTime) detectedAt));
                        } else if (detectedAt instanceof String) {
                            try {
                                LocalDateTime dateTime = LocalDateTime.parse((String) detectedAt);
                                pstmt.setTimestamp(7, Timestamp.valueOf(dateTime));
                            } catch (Exception e) {
                                // パース失敗時は現在時刻を使用
                                pstmt.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                            }
                        } else {
                            pstmt.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                        }
                    } else {
                        pstmt.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                    }

                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.debug("ModSecurityアラート保存成功: access_log_id=" + accessLogId);
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ModSecurityアラート保存エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバー名に対してadmin/operator/viewerロールを追加登録する
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public static void addDefaultRolesForServer(DbSession dbSession, String serverName) throws SQLException {
        if (serverName == null || serverName.trim().isEmpty()) {
            AppLogger.warn("ロール追加時のサーバー名がnull/空です");
            return;
        }
        String[] roles = {"admin", "operator", "viewer"};
        dbSession.execute(conn -> {
            for (String role : roles) {
                String roleName = serverName + "_" + role;
                String description = serverName + "限定" + role;
                String sql = "INSERT IGNORE INTO roles (role_name, description, inherited_roles, created_at, updated_at) VALUES (?, ?, ?, NOW(), NOW())";
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, roleName);
                    pstmt.setString(2, description);
                    pstmt.setString(3, "[]");
                    int affected = pstmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.info("ロール追加: " + roleName);
                    }
                } catch (SQLException e) {
                    AppLogger.error("ロール追加エラー: " + roleName + " - " + e.getMessage());
                }
            }
        });
        // 追加したロールをデフォルトロールの下位ロールとして登録
        try {
            addDefaultRoleHierarchy(serverName);
        } catch (Exception e) {
            AppLogger.warn("ロール階層自動追加エラー: " + serverName + " - " + e.getMessage());
        }
    }

    /**
     * エージェント登録IDを生成
     * @return ユニークな登録ID
     */
    private static String generateAgentRegistrationId() {
        return "agent-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
