package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import java.sql.SQLException;

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
                String sql = "UPDATE access_log SET blocked_by_modsec = ? WHERE id = ?";
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setBoolean(1, blockedByModSec);
                    pstmt.setLong(2, accessLogId);
                    int updated = pstmt.executeUpdate();
                    if (updated > 0) {
                        AppLogger.debug("ModSecurityブロック状態更新: ID=" + accessLogId + ", blocked=" + blockedByModSec);
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
}