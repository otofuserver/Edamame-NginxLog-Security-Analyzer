package com.edamame.security;

import java.sql.*;
import java.util.function.BiConsumer;

/**
 * serversテーブル構造修正ユーティリティ
 * 重複カラムの統合と正規化を行う
 */
public class ServersTableFixer {

    /**
     * serversテーブルの重複カラム問題を修正
     * @param conn データベース接続
     * @param logFunc ログ出力関数
     * @return 修正成功可否
     */
    public static boolean fixServersTableSchema(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {

                // 重複カラムの確認
                boolean hasDescription = false;
                boolean hasServerDescription = false;
                boolean hasLastActivityAt = false;
                boolean hasLastLogReceived = false;
                boolean hasLogPath = false;

                try (ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM servers")) {
                    while (rs.next()) {
                        String columnName = rs.getString("Field");
                        switch (columnName) {
                            case "description" -> hasDescription = true;
                            case "server_description" -> hasServerDescription = true;
                            case "last_activity_at" -> hasLastActivityAt = true;
                            case "last_log_received" -> hasLastLogReceived = true;
                            case "log_path" -> hasLogPath = true;
                        }
                    }
                }

                log.accept("serversテーブル構造確認完了", "DEBUG");

                // 重複カラムの統合処理
                if (hasDescription && hasServerDescription) {
                    // descriptionからserver_descriptionにデータを移行
                    stmt.execute("""
                        UPDATE servers 
                        SET server_description = COALESCE(NULLIF(server_description, ''), description) 
                        WHERE server_description IS NULL OR server_description = ''
                        """);

                    // 古いdescriptionカラムを削除
                    stmt.execute("ALTER TABLE servers DROP COLUMN description");
                    log.accept("serversテーブル: 重複カラム'description'を削除し、'server_description'に統合しました", "INFO");
                }

                if (hasLastActivityAt && hasLastLogReceived) {
                    // last_activity_atからlast_log_receivedにデータを移行
                    stmt.execute("""
                        UPDATE servers 
                        SET last_log_received = COALESCE(last_log_received, last_activity_at) 
                        WHERE last_log_received IS NULL
                        """);

                    // 古いlast_activity_atカラムを削除
                    stmt.execute("ALTER TABLE servers DROP COLUMN last_activity_at");
                    log.accept("serversテーブル: 重複カラム'last_activity_at'を削除し、'last_log_received'に統合しました", "INFO");
                } else if (hasLastActivityAt && !hasLastLogReceived) {
                    // last_activity_atをlast_log_receivedにリネーム
                    stmt.execute("ALTER TABLE servers CHANGE COLUMN last_activity_at last_log_received DATETIME COMMENT '最終ログ受信時刻'");
                    log.accept("serversテーブル: 'last_activity_at'を'last_log_received'にリネームしました", "INFO");
                }

                // 必要なカラムの追加
                if (!hasLogPath) {
                    stmt.execute("ALTER TABLE servers ADD COLUMN log_path VARCHAR(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'ログファイルパス'");
                    log.accept("serversテーブル: log_pathカラムを追加しました", "INFO");
                }

                if (!hasServerDescription && !hasDescription) {
                    stmt.execute("ALTER TABLE servers ADD COLUMN server_description TEXT COLLATE utf8mb4_unicode_ci COMMENT 'サーバーの説明'");
                    log.accept("serversテーブル: server_descriptionカラムを追加しました", "INFO");
                }

                if (!hasLastLogReceived && !hasLastActivityAt) {
                    stmt.execute("ALTER TABLE servers ADD COLUMN last_log_received DATETIME COMMENT '最終ログ受信時刻'");
                    log.accept("serversテーブル: last_log_receivedカラムを追加しました", "INFO");
                }

                // serversテーブルの照合順序を統一
                stmt.execute("ALTER TABLE servers MODIFY COLUMN server_name VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL UNIQUE");

                // log_pathカラムが存在する場合のみ照合順序を修正
                if (hasLogPath || !hasLogPath) { // 追加後は必ず存在
                    stmt.execute("ALTER TABLE servers MODIFY COLUMN log_path VARCHAR(500) COLLATE utf8mb4_unicode_ci DEFAULT ''");
                }

                conn.commit();
                conn.setAutoCommit(originalAutoCommit);

                log.accept("serversテーブルの構造正規化が完了しました", "INFO");
                return true;

            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(originalAutoCommit);
                log.accept("serversテーブル構造修正エラー: " + e.getMessage(), "ERROR");
                return false;
            }

        } catch (SQLException e) {
            log.accept("serversテーブル修正処理エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * serversテーブルの現在の構造を表示
     * @param conn データベース接続
     * @param logFunc ログ出力関数
     */
    public static void showServersTableStructure(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SHOW COLUMNS FROM servers");

            log.accept("=== serversテーブル構造 ===", "INFO");
            while (rs.next()) {
                String field = rs.getString("Field");
                String type = rs.getString("Type");
                String nullFlag = rs.getString("Null");
                String key = rs.getString("Key");
                String defaultValue = rs.getString("Default");

                log.accept(String.format("  %s: %s (Null: %s, Key: %s, Default: %s)",
                    field, type, nullFlag, key, defaultValue), "INFO");
            }
            log.accept("========================", "INFO");

        } catch (SQLException e) {
            log.accept("serversテーブル構造表示エラー: " + e.getMessage(), "ERROR");
        }
    }
}
