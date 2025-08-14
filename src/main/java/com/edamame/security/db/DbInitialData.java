package com.edamame.security.db;
import com.edamame.security.tools.AppLogger;
import com.edamame.web.security.BCryptPasswordEncoder;
import java.sql.*;

/**
 * DB初期データ投入ユーティリティ
 * settings/roles/users/action_tools/action_rules等の初期レコードを投入する
 * v2.1.0: DbService static化に対応、DbSessionを直接受け取る設計に変更
 */
public class DbInitialData {
    
    /**
     * 初期データを挿入する
     * @param dbSession データベースセッション
     * @param appVersion アプリケーションバージョン
     */
    public static void initializeDefaultData(DbSession dbSession, String appVersion) throws SQLException {
        try {
            // settingsテーブル初期データ挿入
            initializeSettingsTable(dbSession, appVersion);

            // rolesテーブル初期データ挿入
            initializeRolesTable(dbSession);

            // usersテーブル初期デ��タ挿入
            initializeUsersTable(dbSession);

            // action_toolsテーブル初期データ挿入
            initializeActionToolsTable(dbSession);

            // action_rulesテーブル初期データ挿入
            initializeActionRulesTable(dbSession);

        } catch (RuntimeException e) {
            AppLogger.error("初期データ投入でエラー: " + e.getMessage());
            throw new SQLException(e);
        }
    }

    /**
     * settingsテーブルの初期データを挿入
     */
    private static void initializeSettingsTable(DbSession dbSession, String appVersion) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM settings")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    String[][] initialSettings = {
                        {"app_version", appVersion, "アプリケーションバージョン"},
                        {"whitelist_enabled", "true", "ホワイトリスト機能有効化"},
                        {"auto_whitelist_threshold", "100", "自動ホワイトリスト化の閾値"},
                        {"whitelist_check_interval_minutes", "10", "��ワイトリストチェック間隔（分）"},
                        {"log_retention_days", "30", "ログ保持日数"},
                        {"alert_notification_enabled", "true", "アラート通知有効化"}
                    };

                    String insertSql = "INSERT INTO settings (setting_key, setting_value, description) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] setting : initialSettings) {
                            pstmt.setString(1, setting[0]);
                            pstmt.setString(2, setting[1]);
                            pstmt.setString(3, setting[2]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("settingsテーブル初期データ挿入完了");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("settings��ーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * rolesテーブルの初期データを挿入
     */
    private static void initializeRolesTable(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM roles")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    String[][] initialRoles = {
                        {"admin", "管理者"},
                        {"operator", "オペレーター"},
                        {"viewer", "閲覧者"}
                    };

                    String insertSql = "INSERT INTO roles (role_name, description) VALUES (?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] role : initialRoles) {
                            pstmt.setString(1, role[0]);
                            pstmt.setString(2, role[1]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("rolesテーブル初期データ挿入完了");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("rolesテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * usersテーブルの初期データを挿入
     */
    private static void initializeUsersTable(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM users")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
                    String hashedPassword = passwordEncoder.encode("admin123");

                    String insertSql = "INSERT INTO users (username, email, password_hash, role_id, is_active) VALUES (?, ?, ?, 1, TRUE)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        pstmt.setString(1, "admin");
                        pstmt.setString(2, "admin@example.com");
                        pstmt.setString(3, hashedPassword);
                        pstmt.executeUpdate();
                        AppLogger.info("usersテーブル初期データ挿入完了 (デフォルト管理者: admin/admin123)");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("usersテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * action_toolsテーブルの初期データを挿入
     */
    private static void initializeActionToolsTable(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM action_tools")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    String[][] initialTools = {
                        {"iptables", "iptables -I INPUT -s {ip} -j DROP", "iptablesによるIP遮断"},
                        {"fail2ban", "fail2ban-client set nginx-limit-req banip {ip}", "fail2banによるIP遮断"},
                        {"notification", "curl -X POST -H 'Content-Type: application/json' -d '{\"text\":\"Attack detected from {ip}\"}' {webhook_url}", "Slack/Discord通知"}
                    };

                    String insertSql = "INSERT INTO action_tools (tool_name, command_template, description) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] tool : initialTools) {
                            pstmt.setString(1, tool[0]);
                            pstmt.setString(2, tool[1]);
                            pstmt.setString(3, tool[2]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("action_toolsテーブル初期データ挿入完了");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("action_toolsテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * action_rulesテーブルの初期データを挿入
     */
    private static void initializeActionRulesTable(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM action_rules")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    String[][] initialRules = {
                        {"high_severity_block", "severity >= 3", "1", "TRUE", "高レベル攻撃の自動遮断"},
                        {"repeated_attack_block", "attack_count >= 5", "1", "TRUE", "繰り返し攻撃の自動遮断"},
                        {"critical_attack_notify", "severity >= 4", "3", "TRUE", "重要攻撃の通知"}
                    };

                    String insertSql = "INSERT INTO action_rules (rule_name, condition_expression, action_tool_id, is_active, description) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] rule : initialRules) {
                            pstmt.setString(1, rule[0]);
                            pstmt.setString(2, rule[1]);
                            pstmt.setInt(3, Integer.parseInt(rule[2]));
                            pstmt.setBoolean(4, Boolean.parseBoolean(rule[3]));
                            pstmt.setString(5, rule[4]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("action_rulesテーブル初期データ挿入完了");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("action_rulesテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
