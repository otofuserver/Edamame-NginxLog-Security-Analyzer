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
}
