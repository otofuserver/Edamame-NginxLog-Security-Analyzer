package com.edamame.security.db;

import com.edamame.security.tools.AppLogger;

import java.sql.SQLException;

/**
 * DBログ自動削除バッチ処理ユーティリティ
 * settingsテーブルの保存日数に従い、各テーブルの古いレコードを削除する
 * v2.0.0: Connection引数を完全廃止、DbService専用に統一
 */
public class DbDelete {

    /**
     * ログ自動削除バッチ処理（DbService使用）
     * @param dbService データベースサービス
     */
    public static void runLogCleanupBatch(DbService dbService) {
        try {
            dbService.getSession().execute(conn -> {
                try {
                    // settingsテーブルから保存日数設定を取得
                    String settingsSql = "SELECT access_log_retention_days, login_history_retention_days, action_execution_log_retention_days FROM settings WHERE id = 1";

                    try (var pstmt = conn.prepareStatement(settingsSql)) {
                        var rs = pstmt.executeQuery();

                        if (rs.next()) {
                            int accessLogDays = rs.getInt("access_log_retention_days");
                            int loginHistoryDays = rs.getInt("login_history_retention_days");
                            int actionExecLogDays = rs.getInt("action_execution_log_retention_days");

                            // access_log + modsec_alerts（modsec_alertsを先に削除）
                            if (!rs.wasNull() && accessLogDays >= 0) {
                                deleteOldModSecAlerts(dbService, accessLogDays);
                                deleteOldAccessLogs(dbService, accessLogDays);
                            }

                            // login_history
                            if (!rs.wasNull() && loginHistoryDays >= 0) {
                                deleteOldLoginHistory(dbService, loginHistoryDays);
                            }

                            // action_execution_log
                            if (!rs.wasNull() && actionExecLogDays >= 0) {
                                deleteOldActionExecutionLog(dbService, actionExecLogDays);
                            }

                            // agent_servers（access_log_retention_days基準）
                            if (!rs.wasNull() && accessLogDays >= 0) {
                                deleteOldAgentServers(dbService, accessLogDays);
                            }
                        }
                    }
                } catch (Exception e) {
                    AppLogger.error("ログ自動削除バッチ処理でエラー: " + e.getMessage());
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            AppLogger.error("ログ自動削除バッチ全体でエラー: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 古いModSecurityアラートを削除
     */
    private static void deleteOldModSecAlerts(DbService dbService, int retentionDays) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = """
                    DELETE FROM modsec_alerts
                    WHERE detected_at < DATE_SUB(NOW(), INTERVAL ? DAY)
                       OR access_log_id IN (
                           SELECT id FROM access_log
                           WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
                       )
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    pstmt.setInt(2, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    AppLogger.info("modsec_alerts: " + deleted + "件の古いレコードを削除");
                }
            } catch (SQLException e) {
                AppLogger.error("ModSecアラート削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古いアクセスログを削除
     */
    private static void deleteOldAccessLogs(DbService dbService, int retentionDays) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = "DELETE FROM access_log WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    AppLogger.info("access_log: " + deleted + "件の古いレコードを削除");
                }
            } catch (SQLException e) {
                AppLogger.error("アクセスログ削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 古いエージェントサーバー記録を削除
     */
    private static void deleteOldAgentServers(DbService dbService, int retentionDays) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = """
                    DELETE FROM agent_servers
                    WHERE last_heartbeat < DATE_SUB(NOW(), INTERVAL ? DAY)
                      AND status != 'active'
                    """;

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    AppLogger.info("agent_servers: " + deleted + "件の古いレコードを削除");
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
    private static void deleteOldLoginHistory(DbService dbService, int retentionDays) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = "DELETE FROM login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    AppLogger.info("login_history: " + deleted + "件の古いレコードを削除");
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
    private static void deleteOldActionExecutionLog(DbService dbService, int retentionDays) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = "DELETE FROM action_execution_log WHERE execution_time < DATE_SUB(NOW(), INTERVAL ? DAY)";

                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, retentionDays);
                    int deleted = pstmt.executeUpdate();
                    AppLogger.info("action_execution_log: " + deleted + "件の古いレコードを削除");
                }
            } catch (SQLException e) {
                AppLogger.error("アクション実行ログ削除エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
