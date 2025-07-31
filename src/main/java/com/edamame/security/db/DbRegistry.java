package com.edamame.security.db;

import java.sql.*;
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
            // 照合順序を明示的に指定してサーバーの存在確認
            String checkSql = """
                SELECT id, server_description, log_path
                FROM servers
                WHERE server_name = ? COLLATE utf8mb4_unicode_ci
                """;

            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, serverName);
                ResultSet rs = checkStmt.executeQuery();

                if (rs.next()) {
                    // サーバーが存在する場合は更新
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
                    }
                } else {
                    // サーバーが存在しない場合���新規登録
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
            }

        } catch (SQLException e) {
            log.accept("サーバー登録・更新エラー: " + e.getMessage(), "ERROR");
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
        if (serverName == null || serverName.trim().isEmpty()) {
            return;
        }
        try {
            String updateSql = """
                UPDATE servers
                SET last_log_received = NOW()
                WHERE server_name = ? COLLATE utf8mb4_unicode_ci
                """;
            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, serverName);
                int updated = updateStmt.executeUpdate();
                if (updated == 0) {
                    // サーバーが存在しない場合は自動登録
                    registerOrUpdateServer(conn, serverName, "自動検出されたサーバー", "", log);
                }
            }
        } catch (SQLException e) {
            log.accept("最終ログ受信時刻更新エラー: " + e.getMessage(), "WARN");
        }
    }
}
