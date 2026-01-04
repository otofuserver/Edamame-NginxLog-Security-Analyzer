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
                    // 挿入順により自動採番される id を利用して継承関係を初期化します。
                    // 想定: 1=admin, 2=operator, 3=viewer
                    // admin は operator と viewer を継承、operator は viewer を継承します。
                    String[][] initialRoles = {
                        {"admin", "管理者", "[2,3,4]"},
                        {"operator", "オペレーター", "[3]"},
                        {"viewer", "閲覧者", "[]"},
                        {"auditor", "監査", "[]"}
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

                    // カラム存在チェック（互換性対応）
                    boolean hasCommandTemplate = hasColumn(conn, "action_tools", "command_template");
                    boolean hasToolType = hasColumn(conn, "action_tools", "tool_type");
                    boolean hasConfigJson = hasColumn(conn, "action_tools", "config_json");
                    boolean hasIsEnabled = hasColumn(conn, "action_tools", "is_enabled");

                    // 挿入カラムを動的に構築
                    java.util.List<String> cols = new java.util.ArrayList<>();
                    cols.add("tool_name");
                    if (hasCommandTemplate) cols.add("command_template");
                    if (hasToolType) cols.add("tool_type");
                    if (hasConfigJson) cols.add("config_json");
                    if (hasIsEnabled) cols.add("is_enabled");
                    cols.add("description");

                    String insertSql = buildInsertSql("action_tools", cols);
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] tool : initialTools) {
                            int idx = 1;
                            // cols の順序に従って値を設定する
                            for (String col : cols) {
                                switch (col) {
                                    case "tool_name":
                                        pstmt.setString(idx++, tool[0]);
                                        break;
                                    case "command_template":
                                        pstmt.setString(idx++, tool[1]);
                                        break;
                                    case "tool_type":
                                        pstmt.setString(idx++, "shell");
                                        break;
                                    case "config_json":
                                        pstmt.setString(idx++, null);
                                        break;
                                    case "is_enabled":
                                        pstmt.setBoolean(idx++, true);
                                        break;
                                    case "description":
                                        pstmt.setString(idx++, tool[2]);
                                        break;
                                    default:
                                        pstmt.setObject(idx++, null);
                                        break;
                                }
                            }
                             pstmt.addBatch();
                        }
                        pstmt.executeBatch();
                        AppLogger.info("action_toolsテーブル初期データ挿入完了 (互換モード)");
                    }
                }
            } catch (SQLException e) {
                AppLogger.error("action_toolsテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    // 互換的にINSERT文を作るユーティリティ
    private static String buildInsertSql(String tableName, java.util.List<String> columns) {
        StringBuilder sb = new StringBuilder();
        sb.append("INSERT INTO ").append(tableName).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("?");
        }
        sb.append(")");
        return sb.toString();
    }

    // テーブルにカラムが存在するかを判定するユーティリティ
    private static boolean hasColumn(Connection conn, String tableName, String columnName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SHOW COLUMNS FROM " + tableName + " LIKE ?")) {
            ps.setString(1, columnName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
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

                    boolean hasConditionExpression = hasColumn(conn, "action_rules", "condition_expression");
                    boolean hasConditionType = hasColumn(conn, "action_rules", "condition_type");
                    boolean hasConditionParams = hasColumn(conn, "action_rules", "condition_params");
                    boolean hasIsActive = hasColumn(conn, "action_rules", "is_active");
                    boolean hasIsEnabled = hasColumn(conn, "action_rules", "is_enabled");
                    boolean hasDescription = hasColumn(conn, "action_rules", "description");
                    boolean hasTargetServer = hasColumn(conn, "action_rules", "target_server");

                    // 動的に挿入カラムを決定
                    java.util.List<String> cols = new java.util.ArrayList<>();
                    cols.add("rule_name");
                    if (hasConditionExpression) cols.add("condition_expression");
                    if (hasConditionType) cols.add("condition_type");
                    if (hasConditionParams) cols.add("condition_params");
                    if (hasTargetServer) cols.add("target_server");
                    cols.add("action_tool_id");
                    if (hasIsActive) cols.add("is_active");
                    if (hasIsEnabled && !hasIsActive) cols.add("is_enabled");
                    if (hasDescription) cols.add("description");

                    String insertSql = buildInsertSql("action_rules", cols);
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                        for (String[] rule : initialRules) {
                            int idx = 1;
                            // cols の順序に従って値を設定する
                            for (String col : cols) {
                                switch (col) {
                                    case "rule_name":
                                        pstmt.setString(idx++, rule[0]);
                                        break;
                                    case "condition_expression":
                                        pstmt.setString(idx++, rule[1]);
                                        break;
                                    case "condition_type":
                                        // 現行仕様では表現形式のマッピングを 'expression' として格納
                                        pstmt.setString(idx++, "expression");
                                        break;
                                    case "condition_params":
                                        pstmt.setString(idx++, rule[1]);
                                        break;
                                    case "target_server":
                                        pstmt.setString(idx++, "*");
                                        break;
                                    case "action_tool_id":
                                        pstmt.setInt(idx++, Integer.parseInt(rule[2]));
                                        break;
                                    case "is_active":
                                        pstmt.setBoolean(idx++, Boolean.parseBoolean(rule[3]));
                                        break;
                                    case "is_enabled":
                                        pstmt.setBoolean(idx++, Boolean.parseBoolean(rule[3]));
                                        break;
                                    case "description":
                                        pstmt.setString(idx++, rule[4]);
                                        break;
                                    default:
                                        pstmt.setObject(idx++, null);
                                        break;
                                }
                            }
                            pstmt.addBatch();
                         }
                         pstmt.executeBatch();
                         AppLogger.info("action_rulesテーブル初期データ挿入完了 (互換モード)");
                     }
                }
            } catch (SQLException e) {
                AppLogger.error("action_rulesテーブル初期データ挿入エラー: " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }
}
