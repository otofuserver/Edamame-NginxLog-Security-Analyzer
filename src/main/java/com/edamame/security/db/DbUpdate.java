package com.edamame.security.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.BiConsumer;

/**
 * データベースのアップデート処理用クラス
 * サーバー・エージェント・統計情報などのUPDATE系処理を集約
 */
public class DbUpdate {
    /**
     * サーバー情報を更新
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @param logFunc ログ出力関数
     */
    public static void updateServerInfo(Connection conn, String serverName, String description, String logPath, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        String updateSql = """
            UPDATE servers
            SET server_description = ?,
                log_path = ?,
                last_log_received = NOW(),
                updated_at = NOW()
            WHERE server_name = ? COLLATE utf8mb4_unicode_ci
            """;
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setString(1, description != null ? description : "");
            updateStmt.setString(2, logPath != null ? logPath : "");
            updateStmt.setString(3, serverName);
            int updated = updateStmt.executeUpdate();
            if (updated > 0) {
                log.accept("サーバー情報を更新しました: " + serverName, "DEBUG");
            }
        } catch (SQLException e) {
            log.accept("サーバー情報更新エラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * サーバーの最終ログ受信時刻を更新
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param logFunc ログ出力関数
     */
    public static void updateServerLastLogReceived(Connection conn, String serverName, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        String updateSql = """
            UPDATE servers
            SET last_log_received = NOW()
            WHERE server_name = ? COLLATE utf8mb4_unicode_ci
            """;
        try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
            updateStmt.setString(1, serverName);
            int updated = updateStmt.executeUpdate();
            if (updated == 0) {
                log.accept("サーバー最終ログ受信時刻更新失敗: " + serverName, "WARN");
            }
        } catch (SQLException e) {
            log.accept("最終ログ受信時刻更新エラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * エージェントのハートビートを更新
     * @param conn データベース接続
     * @param registrationId エージェント登録ID
     * @param logFunc ログ出力関数
     * @return 更新された行数
     */
    public static int updateAgentHeartbeat(Connection conn, String registrationId, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        try {
            String sql = "UPDATE agent_servers SET last_heartbeat = NOW(), tcp_connection_count = tcp_connection_count + 1 WHERE registration_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, registrationId);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    log.accept("ハートビート更新成功: " + registrationId, "DEBUG");
                } else {
                    log.accept("ハートビート更新失敗 - 登録ID未発見: " + registrationId, "WARN");
                }
                return updated;
            }
        } catch (SQLException e) {
            log.accept("ハートビート更新でエラー: " + e.getMessage(), "ERROR");
            return 0;
        }
    }

    /**
     * エージェントのログ処理統計を更新
     * @param conn データベース接続
     * @param registrationId エージェント登録ID
     * @param logCount 処理したログ件数
     * @param logFunc ログ出力関数
     * @return 更新された行数
     */
    public static int updateAgentLogStats(Connection conn, String registrationId, int logCount, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        try {
            String sql = "UPDATE agent_servers SET last_log_count = ?, total_logs_received = total_logs_received + ? WHERE registration_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, logCount);
                pstmt.setInt(2, logCount);
                pstmt.setString(3, registrationId);
                int updated = pstmt.executeUpdate();
                if (updated > 0) {
                    log.accept("ログ統計更新成功: " + registrationId + " (" + logCount + "件)", "DEBUG");
                } else {
                    log.accept("ログ統計更新失敗 - 登録ID未発見: " + registrationId, "WARN");
                }
                return updated;
            }
        } catch (SQLException e) {
            log.accept("ログ統計更新でエラー: " + e.getMessage(), "ERROR");
            return 0;
        }
    }


    /**
     * 特定エージェントをinactive状態に変更
     * エージェント個別終了時に呼び出される
     *
     * @param conn データベース接続
     * @param registrationId エージェント登録ID
     * @param logFunc ログ出力関数
     * @return 更新された行数
     */
    public static int deactivateAgent(Connection conn, String registrationId, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
                (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            String sql = "UPDATE agent_servers SET status = 'inactive' WHERE registration_id = ? AND status = 'active'";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, registrationId);
                int affectedRows = pstmt.executeUpdate();

                if (affectedRows > 0) {
                    log.accept("エージェント " + registrationId + " をinactive状態に変更しました", "INFO");
                } else {
                    log.accept("エージェント " + registrationId + " の inactive化: 対象なし（既にinactive or 存在しない）", "DEBUG");
                }

                return affectedRows;
            }
        } catch (SQLException e) {
            log.accept("エージェント " + registrationId + " のinactive化でエラー: " + e.getMessage(), "ERROR");
            return 0;
        }
    }

    /**
     * 全アクティブエージェントをinactive状態に変更
     * サーバー終了時に呼び出される
     *
     * @param conn データベース接続
     * @param logFunc ログ出力関数
     */
    public static void deactivateAllAgents(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
                (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            String sql = "UPDATE agent_servers SET status = 'inactive' WHERE status = 'active'";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows > 0) {
                    log.accept("サーバー終了により " + affectedRows + " 個のエージェントをinactive状態に変更しました", "INFO");
                } else {
                    log.accept("inactive化対象のアクティブエージェントはありませんでした", "INFO");
                }
            }
        } catch (SQLException e) {
            log.accept("エージェント一括inactive化でエラー: " + e.getMessage(), "ERROR");
        }
    }
}