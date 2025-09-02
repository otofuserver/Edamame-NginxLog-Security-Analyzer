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
     */
    public static void initializeDefaultData(DbSession dbSession) throws SQLException {
        try {
            // settingsテーブル初期データ挿入
            initializeSettingsTable(dbSession);

            // rolesテーブル初期データ挿入
            initializeRolesTable(dbSession);

            // usersテーブル初期データ挿入
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
     * settingsテーブルの初期データを挿入（現行スキーマに合わせて修正）
     */
    private static void initializeSettingsTable(DbSession dbSession) throws SQLException {
        dbSession.execute(conn -> {
            try {
                boolean isEmpty;
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM settings")) {
                    ResultSet rs = pstmt.executeQuery();
                    isEmpty = rs.next() && rs.getInt(1) == 0;
                }

                if (isEmpty) {
                    // settingsテーブルはid=1の単一レコードのみ
                    String insertSql = "INSERT INTO settings (id, whitelist_mode, whitelist_ip, log_retention_days) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        pstmt.setInt(1, 1);
                        pstmt.setBoolean(2, false); // デフォルト: ホワイトリスト無効
                        pstmt.setString(3, "");    // デフォルト: 空
                        pstmt.setInt(4, 365);       // デフォルト: 365日
                        pstmt.executeUpdate();
                        AppLogger.info("settingsテーブル初期データ挿入完了（id=1固定、現行スキーマ対応）");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("settingsテーブル初期データ挿入エラー: " + e.getMessage());
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
                        {"admin", "管理者", "[]"},
                        {"operator", "オペレーター", "[]"},
                        {"viewer", "閲覧者", "[]"}
                    };

                    String insertSql = "INSERT INTO roles (role_name, description, inherited_roles) VALUES (?, ?, ?)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] role : initialRoles) {
                            pstmt.setString(1, role[0]);
                            pstmt.setString(2, role[1]);
                            pstmt.setString(3, role[2]);
                            pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("rolesテーブル初期データ挿入完了 (inherited_rolesカラム対応)");
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

                    String insertUserSql = "INSERT INTO users (username, email, password_hash, is_active) VALUES (?, ?, ?, TRUE)";
                    try (PreparedStatement pstmt = conn.prepareStatement(insertUserSql, Statement.RETURN_GENERATED_KEYS)) {
                        pstmt.setString(1, "admin");
                        pstmt.setString(2, "admin@example.com");
                        pstmt.setString(3, hashedPassword);
                        pstmt.executeUpdate();
                        AppLogger.info("usersテーブル初期データ挿入完了 (デフォルト管理者: admin/admin123)");

                        // adminユーザーID取得
                        int adminUserId = 0;
                        try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                adminUserId = generatedKeys.getInt(1);
                            } else {
                                // fallback: ユーザー名で取得
                                try (PreparedStatement ps2 = conn.prepareStatement("SELECT id FROM users WHERE username = ?")) {
                                    ps2.setString(1, "admin");
                                    try (ResultSet rs2 = ps2.executeQuery()) {
                                        if (rs2.next()) adminUserId = rs2.getInt(1);
                                    }
                                }
                            }
                        }
                        // adminロールID取得
                        int adminRoleId = 1;
                        try (PreparedStatement psRole = conn.prepareStatement("SELECT id FROM roles WHERE role_name = ?")) {
                            psRole.setString(1, "admin");
                            try (ResultSet rsRole = psRole.executeQuery()) {
                                if (rsRole.next()) adminRoleId = rsRole.getInt(1);
                            }
                        }
                        // users_rolesへadmin権限付与
                        String insertUserRoleSql = "INSERT INTO users_roles (user_id, role_id) VALUES (?, ?)";
                        try (PreparedStatement psUserRole = conn.prepareStatement(insertUserRoleSql)) {
                            psUserRole.setInt(1, adminUserId);
                            psUserRole.setInt(2, adminRoleId);
                            psUserRole.executeUpdate();
                            AppLogger.info("users_rolesテーブルにadminユーザーへadminロールを付与");
                        }
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
