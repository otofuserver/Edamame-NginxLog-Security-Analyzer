package com.edamame.web.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.edamame.security.tools.AppLogger;
/**
 * 認証関連のユーティリティクラス
 * 本番環境向けの最小限のヘルスチェック機能のみを提供
 */
public class AuthDebugUtils {

    /**
     * データベース内のユーザー情報をデバッグ表示
     * @param connection データベース接続
     */
    public static void verifyAuthenticationSystem(Connection connection) {
        try {
            // usersテーブルの存在確認
            String checkTableSql = "SHOW TABLES LIKE 'users'";
            try (PreparedStatement stmt = connection.prepareStatement(checkTableSql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    AppLogger.info("認証システム確認: usersテーブルが存在します");
                } else {
                    AppLogger.warn("警告: usersテーブルが見つかりません");
                    return;
                }
            }

            // adminユーザーの存在確認
            String checkUserSql = "SELECT COUNT(*) FROM users WHERE username = 'admin' AND is_active = TRUE";
            try (PreparedStatement stmt = connection.prepareStatement(checkUserSql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    AppLogger.info("認証システム確認: adminユーザーが利用可能です");
                } else {
                    AppLogger.warn("警告: adminユーザーが見つからないか無効です");
                }
            }

        } catch (SQLException e) {
            AppLogger.warn("認証シ���テム確認エラー: " + e.getMessage());
        }
    }

    /**
     * 本番環境向けの簡易認証テスト
     * @param connection データベース接続
     */
    public static void performHealthCheck(Connection connection) {
        try {
            // BCryptPasswordEncoderの動作確認
            BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
            String testHash = encoder.encode("test");
            boolean isWorking = encoder.matches("test", testHash);
            
            if (isWorking) {
                AppLogger.info("認証システムヘルスチェック: 正常");
            } else {
                AppLogger.warn("警告: BCryptPasswordEncoderに問題があります");
            }

        } catch (Exception e) {
            AppLogger.warn("認証システムヘルスチェックエラー: " + e.getMessage());
        }
    }
}
