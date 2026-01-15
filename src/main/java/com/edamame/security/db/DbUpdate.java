package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * データベースのアップデート処理用クラス
 * サーバー・エージェント・統計情報などのUPDATE系処理を集約
 * v2.1.0: DbService static化に対応、DbSessionを直接受け取る設計に変更
 */
public class DbUpdate {
    
    /**
     * サーバー情報を更新
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void updateServerInfo(DbSession dbSession, String serverName, String description, String logPath) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String updateSql = """
                    UPDATE servers
                    SET server_description = ?,
                        log_path = ?,
                        last_log_received = NOW(),
                        updated_at = NOW()
                    WHERE server_name = ? COLLATE utf8mb4_unicode_ci
                    """;
                try (var updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, description != null ? description : "");
                    updateStmt.setString(2, logPath != null ? logPath : "");
                    updateStmt.setString(3, serverName);
                    int updated = updateStmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("サーバー情報を更新しました: " + serverName);
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("サーバー情報更新エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバーの最終ログ受信時刻を更新
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public static void updateServerLastLogReceived(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String updateSql = """
                    UPDATE servers
                    SET last_log_received = NOW()
                    WHERE server_name = ? COLLATE utf8mb4_unicode_ci
                    """;
                try (var updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, serverName);
                    int updated = updateStmt.executeUpdate();
                    if (updated == 0) {
                        AppLogger.warn("サーバー最終ログ受信時刻更新失敗: " + serverName);
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("最終ログ受信時刻更新エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * エージェントのハートビートを更新
     * @param dbSession データベースセッション
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentHeartbeat(DbSession dbSession, String registrationId) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = "UPDATE agent_servers SET last_heartbeat = NOW(), tcp_connection_count = tcp_connection_count + 1 WHERE registration_id = ?";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, registrationId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("ハートビート更新成功: " + registrationId);
                    } else {
                        AppLogger.warn("ハートビート更新失敗 - 登録ID未発見: " + registrationId);
                    }
                    return updated;
                }
            } catch (SQLException e) {
                AppLogger.error("ハートビート更新でエラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * エージェントのログ処理統計を更新
     * @param dbSession データベースセッション
     * @param registrationId エージェント登録ID
     * @param logCount 処理したログ件数
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentLogStats(DbSession dbSession, String registrationId, int logCount) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = "UPDATE agent_servers SET total_logs_received = total_logs_received + ?, last_log_count = ? WHERE registration_id = ?";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, logCount);
                    pstmt.setInt(2, logCount);
                    pstmt.setString(3, registrationId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("ログ統計更新成功: " + registrationId + " (+" + logCount + " logs)");
                    } else {
                        AppLogger.warn("ログ統計更新失敗 - 登録ID未発見: " + registrationId);
                    }
                    return updated;
                }
            } catch (SQLException e) {
                AppLogger.error("ログ統計更新でエラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * access_logのModSecurityブロック状態を更新
     * @param dbSession データベースセッション
     * @param accessLogId アクセスログID
     * @param blockedByModSec ModSecurityによってブロックされたかどうか
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAccessLogModSecStatus(DbSession dbSession, Long accessLogId, boolean blockedByModSec) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String selectSql = "SELECT server_name, method, full_url, status_code, access_time FROM access_log WHERE id = ?";
                String sql = "UPDATE access_log SET blocked_by_modsec = ? WHERE id = ?";
                String serverName = null; String method = null; String fullUrl = null; Integer statusCode = null; Timestamp accessTime = null;
                try (var sel = conn.prepareStatement(selectSql)) {
                    sel.setLong(1, accessLogId);
                    try (var rs = sel.executeQuery()) {
                        if (rs.next()) {
                            serverName = rs.getString("server_name");
                            method = rs.getString("method");
                            fullUrl = rs.getString("full_url");
                            Object statusObj = rs.getObject("status_code");
                            statusCode = statusObj instanceof Number n ? n.intValue() : null;
                            accessTime = rs.getTimestamp("access_time");
                        }
                    }
                }

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setBoolean(1, blockedByModSec);
                    pstmt.setLong(2, accessLogId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("ModSecurityブロック状態更新: ID=" + accessLogId + ", blocked=" + blockedByModSec);
                        // url_registryの最新アクセス情報も同期
                        if (serverName != null && method != null && fullUrl != null) {
                            try {
                                updateUrlRegistryLatest(dbSession, serverName, method, fullUrl, accessTime, statusCode, blockedByModSec);
                            } catch (Exception e) {
                                AppLogger.warn("url_registry最新情報同期失敗: " + e.getMessage());
                            }
                        }
                    }
                    return updated;
                }
            } catch (SQLException e) {
                AppLogger.error("ModSecurityブロック状態更新エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 特定エージェントをinactive状態に変更
     * @param dbSession データベースセッション
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int deactivateAgent(DbSession dbSession, String registrationId) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = "UPDATE agent_servers SET status = 'inactive' WHERE registration_id = ?";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, registrationId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.info("エージェントを非アクティブ化: " + registrationId);
                    }
                    return updated;
                }
            } catch (SQLException e) {
                AppLogger.error("エージェント非アクティブ化エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 全アクティブエージェントをinactive状態に変更
     * @param dbSession データベースセッション
     * @throws SQLException SQL例外
     */
    public static void deactivateAllAgents(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "UPDATE agent_servers SET status = 'inactive' WHERE status = 'active'";
                try (var pstmt = conn.prepareStatement(sql)) {
                    int updated = pstmt.executeUpdate();
                    AppLogger.info("全エージェントを非アクティブ化: " + updated + " 件");
                }
            } catch (SQLException e) {
                AppLogger.error("全エージェント非アクティブ化エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * URLをホワイトリスト状態に更新
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateUrlWhitelistStatus(DbSession dbSession, String serverName, String method, String fullUrl) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    UPDATE url_registry
                    SET is_whitelisted = true, updated_at = NOW()
                    WHERE server_name = ? AND method = ? AND full_url = ?
                    """;
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    pstmt.setString(2, method);
                    pstmt.setString(3, fullUrl);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.info("URLをホワイトリスト化: " + method + " " + fullUrl);
                    }
                    return updated;
                }
            } catch (SQLException e) {
                AppLogger.error("URLホワイトリスト化エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバー名に対してadmin/operator/viewerロールの下位ロールIDをrolesテーブルのinherited_roles(JSON配列)に追加登録する
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public static void addDefaultRoleHierarchy(DbSession dbSession, String serverName) throws SQLException {
        if (serverName == null || serverName.trim().isEmpty()) {
            AppLogger.warn("ロール階層追加時のサーバー名がnull/空です");
            return;
        }
        String[] baseRoles = {"admin", "operator", "viewer"};
        String[] childRoleNames = {serverName + "_admin", serverName + "_operator", serverName + "_viewer"};
        dbSession.execute(conn -> {
            for (int i = 0; i < baseRoles.length; i++) {
                int childRoleId;
                String childIdSql = "SELECT id FROM roles WHERE role_name = ?";
                try (var childIdStmt = conn.prepareStatement(childIdSql)) {
                    childIdStmt.setString(1, childRoleNames[i]);
                    try (var rs = childIdStmt.executeQuery()) {
                        if (rs.next()) {
                            childRoleId = rs.getInt(1);
                        } else {
                            AppLogger.warn("下位ロールが存在しません: " + childRoleNames[i]);
                            continue;
                        }
                    }
                } catch (SQLException e) {
                    AppLogger.error("下位ロールID取得エラー: " + childRoleNames[i] + " - " + e.getMessage());
                    continue;
                }
                String selectSql = "SELECT inherited_roles FROM roles WHERE role_name = ?";
                String updateSql = "UPDATE roles SET inherited_roles = ? , updated_at = NOW() WHERE role_name = ?";
                try (var selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setString(1, baseRoles[i]);
                    try (var rs = selectStmt.executeQuery()) {
                        if (rs.next()) {
                            String inheritedJson = rs.getString(1);
                            java.util.List<Integer> inheritedList = new java.util.ArrayList<>();
                            if (inheritedJson != null && !inheritedJson.isBlank() && !inheritedJson.equals("[]")) {
                                // JSON配列をパース（型安全にキャスト）
                                @SuppressWarnings("unchecked")
                                java.util.List<Integer> parsed = (java.util.List<Integer>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(inheritedJson, java.util.List.class);
                                inheritedList.addAll(parsed);
                            }
                            if (!inheritedList.contains(childRoleId)) {
                                inheritedList.add(childRoleId);
                                String newJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inheritedList);
                                try (var updateStmt = conn.prepareStatement(updateSql)) {
                                    updateStmt.setString(1, newJson);
                                    updateStmt.setString(2, baseRoles[i]);
                                    int affected = updateStmt.executeUpdate();
                                    if (affected > 0) {
                                        AppLogger.info("ロール階層(inherited_roles)追加: " + baseRoles[i] + " → id=" + childRoleId);
                                    }
                                }
                            } else {
                                AppLogger.debug("既にinherited_rolesに存在: " + baseRoles[i] + " → id=" + childRoleId);
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.error("ロール階層(inherited_roles)追加エラー: " + baseRoles[i] + " → id=" + childRoleId + " - " + e.getMessage());
                    }
                } catch (Exception e) {
                    AppLogger.error("ロール階層(inherited_roles)追加エラー: " + baseRoles[i] + " → id=" + childRoleId + " - " + e.getMessage());
                }
            }

            // --- 追加: サーバー固有ロール間の継承を作成 ---
            try {
                String adminName = childRoleNames[0];
                String operatorName = childRoleNames[1];
                String viewerName = childRoleNames[2];

                Integer adminId = null, operatorId = null, viewerId = null;
                String idSql = "SELECT id FROM roles WHERE role_name = ?";
                try (var ps = conn.prepareStatement(idSql)) {
                    ps.setString(1, adminName);
                    try (var rs = ps.executeQuery()) { if (rs.next()) adminId = rs.getInt(1); }
                }
                try (var ps = conn.prepareStatement(idSql)) {
                    ps.setString(1, operatorName);
                    try (var rs = ps.executeQuery()) { if (rs.next()) operatorId = rs.getInt(1); }
                }
                try (var ps = conn.prepareStatement(idSql)) {
                    ps.setString(1, viewerName);
                    try (var rs = ps.executeQuery()) { if (rs.next()) viewerId = rs.getInt(1); }
                }

                // operator inherits viewer
                if (operatorId != null && viewerId != null) {
                    String sel = "SELECT inherited_roles FROM roles WHERE role_name = ?";
                    String upd = "UPDATE roles SET inherited_roles = ? , updated_at = NOW() WHERE role_name = ?";
                    try (var selSt = conn.prepareStatement(sel)) {
                        selSt.setString(1, operatorName);
                        try (var rs = selSt.executeQuery()) {
                            if (rs.next()) {
                                String inheritedJson = rs.getString(1);
                                java.util.List<Integer> list = new java.util.ArrayList<>();
                                if (inheritedJson != null && !inheritedJson.isBlank() && !inheritedJson.equals("[]")) {
                                    @SuppressWarnings("unchecked")
                                    java.util.List<Integer> parsed = (java.util.List<Integer>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(inheritedJson, java.util.List.class);
                                    list.addAll(parsed);
                                }
                                if (!list.contains(viewerId)) {
                                    list.add(viewerId);
                                    String newJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
                                    try (var updSt = conn.prepareStatement(upd)) {
                                        updSt.setString(1, newJson);
                                        updSt.setString(2, operatorName);
                                        updSt.executeUpdate();
                                        AppLogger.info("ロール階層(inherited_roles)追加: " + operatorName + " → id=" + viewerId);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.error("サーバー固有ロール間の継承追加エラー(operator->viewer): " + e.getMessage());
                    }
                }

                // admin inherits operator and viewer
                if (adminId != null) {
                    String sel = "SELECT inherited_roles FROM roles WHERE role_name = ?";
                    String upd = "UPDATE roles SET inherited_roles = ? , updated_at = NOW() WHERE role_name = ?";
                    try (var selSt = conn.prepareStatement(sel)) {
                        selSt.setString(1, adminName);
                        try (var rs = selSt.executeQuery()) {
                            if (rs.next()) {
                                String inheritedJson = rs.getString(1);
                                java.util.List<Integer> list = new java.util.ArrayList<>();
                                if (inheritedJson != null && !inheritedJson.isBlank() && !inheritedJson.equals("[]")) {
                                    @SuppressWarnings("unchecked")
                                    java.util.List<Integer> parsed = (java.util.List<Integer>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(inheritedJson, java.util.List.class);
                                    list.addAll(parsed);
                                }
                                boolean updated = false;
                                if (operatorId != null && !list.contains(operatorId)) { list.add(operatorId); updated = true; }
                                if (viewerId != null && !list.contains(viewerId)) { list.add(viewerId); updated = true; }
                                if (updated) {
                                    String newJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
                                    try (var updSt = conn.prepareStatement(upd)) {
                                        updSt.setString(1, newJson);
                                        updSt.setString(2, adminName);
                                        updSt.executeUpdate();
                                        AppLogger.info("ロール階層(inherited_roles)追加: " + adminName + " → operator/viewer ids added");
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.error("サーバー固有ロール間の継承追加エラー(admin->operator/viewer): " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                AppLogger.error("サーバー固有ロール間の継承追加エラー(外側): " + e.getMessage());
            }
        });
    }

    /**
     * url_registryの最新アクセス情報を更新（汎用呼び出し用）
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @param latestAccessTime 最終アクセス時刻
     * @param latestStatusCode 最終HTTPステータス
     * @param latestBlockedByModsec 最終ModSecブロック有無
     * @throws SQLException SQL例外
     */
    public static void updateUrlRegistryLatest(DbSession dbSession, String serverName, String method, String fullUrl,
                                               Timestamp latestAccessTime, Integer latestStatusCode, Boolean latestBlockedByModsec) throws SQLException {
        DbRegistry.updateUrlRegistryLatest(dbSession, serverName, method, fullUrl, latestAccessTime, latestStatusCode, latestBlockedByModsec);
    }
}

