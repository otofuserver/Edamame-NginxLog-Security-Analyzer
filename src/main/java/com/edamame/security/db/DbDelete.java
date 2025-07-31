package com.edamame.security.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.BiConsumer;

/**
 * DBログ自動削除バッチ処理ユーティリティ
 * settingsテーブルの保存日数に従い、各テーブルの古いレコードを削除する
 */
public class DbDelete {
    /**
     * ログ自動削除バッチ処理
     * 削除日数が-1またはNULLなら無効
     * 1日1回実行を想定
     * @param conn データベース接続
     * @param log ログ出力関数
     */
    public static void runLogCleanupBatch(Connection conn, BiConsumer<String, String> log) {
        try (PreparedStatement pstmt = conn.prepareStatement(
            "SELECT access_log_retention_days, login_history_retention_days, action_execution_log_retention_days FROM settings WHERE id = 1")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                int accessLogDays = rs.getInt("access_log_retention_days");
                int loginHistoryDays = rs.getInt("login_history_retention_days");
                int actionExecLogDays = rs.getInt("action_execution_log_retention_days");

                // access_log + modsec_alerts
                if (!rs.wasNull() && accessLogDays >= 0) {
                    try (PreparedStatement delAccess = conn.prepareStatement(
                        "DELETE FROM access_log WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        delAccess.setInt(1, accessLogDays);
                        int deleted = delAccess.executeUpdate();
                        log.accept("access_log: " + deleted + "件の古いレコードを削除", "INFO");
                    }
                    try (PreparedStatement delModsec = conn.prepareStatement(
                        "DELETE FROM modsec_alerts WHERE detected_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        delModsec.setInt(1, accessLogDays);
                        int deleted = delModsec.executeUpdate();
                        log.accept("modsec_alerts: " + deleted + "件の古いレコードを削除", "INFO");
                    }
                }
                // login_history
                if (!rs.wasNull() && loginHistoryDays >= 0) {
                    try (PreparedStatement delLogin = conn.prepareStatement(
                        "DELETE FROM login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        delLogin.setInt(1, loginHistoryDays);
                        int deleted = delLogin.executeUpdate();
                        log.accept("login_history: " + deleted + "件の古いレコードを削除", "INFO");
                    }
                }
                // action_execution_log
                if (!rs.wasNull() && actionExecLogDays >= 0) {
                    try (PreparedStatement delAction = conn.prepareStatement(
                        "DELETE FROM action_execution_log WHERE executed_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        delAction.setInt(1, actionExecLogDays);
                        int deleted = delAction.executeUpdate();
                        log.accept("action_execution_log: " + deleted + "件の古いレコードを削除", "INFO");
                    }
                }
            }
        } catch (SQLException e) {
            log.accept("ログ自動削除バッチ処理でエラー: " + e.getMessage(), "ERROR");
        }
    }
}

