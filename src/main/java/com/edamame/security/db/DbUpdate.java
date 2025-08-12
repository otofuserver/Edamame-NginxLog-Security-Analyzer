package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import java.sql.SQLException;

/**
 * データベースのアップデート処理用クラス
 * サーバー・エージェント・統計情報などのUPDATE系処理を集約
 * v2.0.0: Connection引数を完全廃止、DbService専用に統一
 */
public class DbUpdate {
    
    /**
     * サーバー情報を更新（DbService使用）
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void updateServerInfo(DbService dbService, String serverName, String description, String logPath) throws SQLException {
        dbService.getSession().execute(conn -> {
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
     * サーバーの最終ログ受信時刻を更新（DbService使用）
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public static void updateServerLastLogReceived(DbService dbService, String serverName) throws SQLException {
        dbService.getSession().execute(conn -> {
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
     * エージェントのハートビートを更新（DbService使用）
     * @param dbService データベースサービス
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentHeartbeat(DbService dbService, String registrationId) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
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
     * エージェントのログ処理統計を更新（DbService使用）
     * @param dbService データベースサービス
     * @param registrationId エージェント登録ID
     * @param logCount 処理したログ件数
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentLogStats(DbService dbService, String registrationId, int logCount) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "UPDATE agent_servers SET last_log_count = ?, total_logs_received = total_logs_received + ? WHERE registration_id = ?";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, logCount);
                    pstmt.setInt(2, logCount);
                    pstmt.setString(3, registrationId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("ログ統計更新成功: " + registrationId + " (" + logCount + "件)");
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
     * 特定エージェントをinactive状態に変更（DbService使用）
     * @param dbService データベースサービス
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int deactivateAgent(DbService dbService, String registrationId) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "UPDATE agent_servers SET status = 'inactive' WHERE registration_id = ? AND status = 'active'";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, registrationId);
                    int affectedRows = pstmt.executeUpdate();

                    if (affectedRows > 0) {
                        AppLogger.info("エージェント " + registrationId + " をinactive状態に変更しました");
                    } else {
                        AppLogger.debug("エージェント " + registrationId + " の inactive化: 対象なし（既にinactive or 存在しない）");
                    }

                    return affectedRows;
                }
            } catch (SQLException e) {
                AppLogger.error("エージェント " + registrationId + " のinactive化でエラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 全アクティブエージェントをinactive状態に変更（DbService使用）
     * @param dbService データベースサービス
     * @throws SQLException SQL例外
     */
    public static void deactivateAllAgents(DbService dbService) throws SQLException {
        dbService.getSession().execute(conn -> {
            try {
                String sql = "UPDATE agent_servers SET status = 'inactive' WHERE status = 'active'";
                try (var pstmt = conn.prepareStatement(sql)) {
                    int affectedRows = pstmt.executeUpdate();
                    if (affectedRows > 0) {
                        AppLogger.info("サーバー終了により " + affectedRows + " 個のエージェントをinactive状態に変更しました");
                    } else {
                        AppLogger.info("inactive化対象のアクティブエージェントはありませんでした");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("エージェント一括inactive化でエラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * URLをホワイトリスト状態に更新（DbService使用）
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 更新された��数
     * @throws SQLException SQL例外
     */
    public static int updateUrlWhitelistStatus(DbService dbService, String serverName, String method, String fullUrl) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String updateSql = """
                    UPDATE url_registry
                    SET is_whitelisted = true, updated_at = NOW()
                    WHERE server_name = ? AND method = ? AND full_url = ?
                    """;
                try (var updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, serverName);
                    updateStmt.setString(2, method);
                    updateStmt.setString(3, fullUrl);
                    int affected = updateStmt.executeUpdate();
                    if (affected > 0) {
                        AppLogger.info("URLホワイトリスト状態に更新: " + serverName + " - " + method + " " + fullUrl);
                    }
                    return affected;
                }
            } catch (SQLException e) {
                AppLogger.error("URLホワイトリスト状態更新エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}