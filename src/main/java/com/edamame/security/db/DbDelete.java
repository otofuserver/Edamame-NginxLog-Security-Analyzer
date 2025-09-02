package com.edamame.security.db;

import com.edamame.security.tools.AppLogger;

import java.sql.SQLException;

/**
 * DBログ自動削除バッチ処理ユーティリティ
 * settingsテーブルの保存日数に従い、各テーブルの古いレコードを削除する
 * v2.1.0: DbService static化に対応、DbSessionを直接受け取る設計に変更
 */
public class DbDelete {

    /**
     * ログ自動削除バッチ処理
     * @param dbSession データベースセッション
     */
    public static void runLogCleanupBatch(DbSession dbSession) {
        try {
            dbSession.execute(conn -> {
                try {
                    // settingsテーブルから保存日数設定を取得
                    String settingsSql = "SELECT log_retention_days FROM settings WHERE id = 1";

                    try (var pstmt = conn.prepareStatement(settingsSql)) {
                        var rs = pstmt.executeQuery();

                        if (rs.next()) {
                            int retentionDays = rs.getInt("log_retention_days");

                            if (retentionDays >= 0) {
                                // modsec_alertsを先に削除（外部キー制約のため）
                                deleteOldModSecAlerts(dbSession, retentionDays);

                                // access_logを削除
                                deleteOldAccessLogs(dbSession, retentionDays);

                                // 非アクティブなagent_serversを削除
                                deleteOldAgentServers(dbSession, retentionDays);

                                // ログイン履歴を削除
                                deleteOldLoginHistory(dbSession, retentionDays);

                                // アクション実行ログを削除
                                deleteOldActionExecutionLog(dbSession, retentionDays);

                                AppLogger.info("ログクリーンアップバッチ完了: " + retentionDays + "日以前のデータを削除");
                            }
                        } else {
                            AppLogger.warn("log_retention_days設定が見つかりません");
                        }
                    }
                } catch (Exception e) {
                    AppLogger.error("ログクリーンアップバッチエラー: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            AppLogger.error("ログクリーンアップバッチで致命的エラー: " + e.getMessage());
        }
    }

    /**
     * 古いModSecurityアラートを削除
     */
    private static void deleteOldModSecAlerts(DbSession dbSession, int retentionDays) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = """
                    DELETE FROM modsec_alerts
                    WHERE access_log_id IN (
                        SELECT id FROM access_log
                        WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
                    )
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("古いModSecurityアラートを削除: " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ModSecurityアラート削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古いアクセスログを削除
     */
    private static void deleteOldAccessLogs(DbSession dbSession, int retentionDays) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM access_log WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("古いアクセスログを削除: " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("アクセスログ削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古い非アクティブエージェントサーバーを削除
     */
    private static void deleteOldAgentServers(DbSession dbSession, int retentionDays) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = """
                    DELETE FROM agent_servers
                    WHERE status = 'inactive'
                    AND last_heartbeat < DATE_SUB(NOW(), INTERVAL ? DAY)
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("古い非アクティブエージェントサーバーを削除: " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("エージェントサーバー削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古いログイン履歴を削除
     */
    private static void deleteOldLoginHistory(DbSession dbSession, int retentionDays) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("古いログイン履歴を削除: " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ログイン履歴削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古いアクション実行ログを削除
     */
    private static void deleteOldActionExecutionLog(DbSession dbSession, int retentionDays) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM action_execution_log WHERE execution_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("古いアクション実行ログを削除: " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("アクション実行ログ削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバーに関連するデータを一括削除
     * @param dbSession データベースセッション
     * @param serverName 削除対象のサーバー名
     */
    public static void deleteServerData(DbSession dbSession, String serverName) {
        try {
            dbSession.executeInTransaction(conn -> {
                try {
                    AppLogger.info("サーバーデータ削除開始: " + serverName);
                    
                    // 1. modsec_alertsを削除（外部キー制約のため最初）
                    deleteModSecAlertsByServer(dbSession, serverName);
                    
                    // 2. access_logを削除
                    deleteAccessLogsByServer(dbSession, serverName);
                    
                    // 3. url_registryを削除
                    deleteUrlRegistryByServer(dbSession, serverName);
                    
                    // 4. users_rolesから該当role_idを削除（roles削除前に実行）
                    deleteUsersRolesByServer(dbSession, serverName);
                    
                    // 5. rolesを削除
                    deleteRolesByServer(dbSession, serverName);
                    
                    // 6. serversを削除（最後に実行）
                    deleteServerByName(dbSession, serverName);
                    
                    AppLogger.info("サーバーデータ削除完了: " + serverName);
                    
                } catch (Exception e) {
                    AppLogger.error("サーバーデータ削除エラー: " + serverName + " - " + e.getMessage());
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            AppLogger.error("サーバーデータ削除で致命的エラー: " + serverName + " - " + e.getMessage());
        }
    }

    /**
     * 指定サーバーのModSecurityアラートを削除
     */
    private static void deleteModSecAlertsByServer(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = """
                    DELETE FROM modsec_alerts
                    WHERE access_log_id IN (
                        SELECT id FROM access_log
                        WHERE server_name = ?
                    )
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("ModSecurityアラートを削除: " + serverName + " - " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ModSecurityアラート削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバーのアクセスログを削除
     */
    private static void deleteAccessLogsByServer(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM access_log WHERE server_name = ?";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("アクセスログを削除: " + serverName + " - " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("アクセスログ削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバーのURL登録情報を削除
     */
    private static void deleteUrlRegistryByServer(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM url_registry WHERE server_name = ?";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("URL登録情報を削除: " + serverName + " - " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("URL登録情報削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバーのロールに関連するユーザーロール関連付けを削除
     */
    private static void deleteUsersRolesByServer(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = """
                    DELETE FROM users_roles
                    WHERE role_id IN (
                        SELECT id FROM roles
                        WHERE role_name LIKE CONCAT(?, '_%')
                    )
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("ユーザーロール関連付けを削除: " + serverName + " - " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ユーザーロール関連付け削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバーのロール情報を削除（他のロールのinherited_rolesからも削除）
     */
    private static void deleteRolesByServer(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                // まず削除対象のrole_idを取得
                String selectSql = "SELECT id FROM roles WHERE role_name LIKE CONCAT(?, '_%')";
                java.util.List<Integer> targetRoleIds = new java.util.ArrayList<>();
                
                try (var selectStmt = conn.prepareStatement(selectSql)) {
                    selectStmt.setString(1, serverName);
                    var rs = selectStmt.executeQuery();
                    while (rs.next()) {
                        targetRoleIds.add(rs.getInt("id"));
                    }
                }

                // inherited_rolesから該当するrole_idを削除
                if (!targetRoleIds.isEmpty()) {
                    String updateSql = """
                        UPDATE roles
                        SET inherited_roles = JSON_REMOVE(inherited_roles,
                            JSON_UNQUOTE(JSON_SEARCH(inherited_roles, 'one', ?)))
                        WHERE JSON_SEARCH(inherited_roles, 'one', ?) IS NOT NULL
                        """;
                    
                    for (Integer roleId : targetRoleIds) {
                        try (var updateStmt = conn.prepareStatement(updateSql)) {
                            updateStmt.setString(1, roleId.toString());
                            updateStmt.setString(2, roleId.toString());
                            updateStmt.executeUpdate();
                        }
                    }
                }

                // rolesテーブルから削除
                String deleteSql = "DELETE FROM roles WHERE role_name LIKE CONCAT(?, '_%')";
                try (var deleteStmt = conn.prepareStatement(deleteSql)) {
                    deleteStmt.setString(1, serverName);
                    int deleted = deleteStmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("ロール情報を削除: " + serverName + " - " + deleted + " 件");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("ロール情報削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定サーバー情報を削除
     */
    private static void deleteServerByName(DbSession dbSession, String serverName) throws SQLException {
        dbSession.execute(conn -> {
            try {
                String sql = "DELETE FROM servers WHERE server_name = ?";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    int deleted = pstmt.executeUpdate();
                    if (deleted > 0) {
                        AppLogger.info("サーバー情報を削除: " + serverName + " - " + deleted + " 件");
                    } else {
                        AppLogger.warn("削除対象のサーバーが見つかりません: " + serverName);
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("サーバー情報削除エラー: " + serverName + " - " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
    
    
}
