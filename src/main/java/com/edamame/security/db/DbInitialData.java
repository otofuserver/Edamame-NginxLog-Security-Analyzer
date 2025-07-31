package com.edamame.security.db;

import com.edamame.web.security.BCryptPasswordEncoder;
import java.sql.*;
import java.util.function.BiConsumer;

/**
 * DB初期データ投入ユーティリティ
 * settings/roles/users/action_tools/action_rules等の初期レコードを投入する
 */
public class DbInitialData {
    /**
     * 初期データを挿入する
     * @param conn データベース接続
     * @param appVersion アプリケーションバージョン
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    public static void initializeDefaultData(Connection conn, String appVersion, BiConsumer<String, String> log) throws SQLException {
        // settingsテーブルが空なら初期レコード挿入
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM settings")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO settings (id, whitelist_mode, whitelist_ip, backend_version, frontend_version, access_log_retention_days, login_history_retention_days, action_execution_log_retention_days) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insertStmt.setInt(1, 1);
                    insertStmt.setBoolean(2, false);
                    insertStmt.setString(3, "");
                    insertStmt.setString(4, appVersion);
                    insertStmt.setString(5, "");
                    insertStmt.setInt(6, 365);
                    insertStmt.setInt(7, 365);
                    insertStmt.setInt(8, 365);
                    insertStmt.executeUpdate();
                    log.accept("settingsテーブルに初期レコードを挿入しました", "INFO");
                }
            }
        }

        // rolesテーブルが空なら初期ロールを追加
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM roles")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                String[][] initialRoles = {
                    {"administrator", "管理者：すべての機能にアクセス可能"},
                    {"monitor", "監視メンバー：ログ閲覧と基本的な分析機能のみ"}
                };
                try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO roles (role_name, description) VALUES (?, ?)")) {
                    for (String[] role : initialRoles) {
                        insertStmt.setString(1, role[0]);
                        insertStmt.setString(2, role[1]);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                    log.accept("初期ロール（administrator, monitor）を作成しました", "INFO");
                }
            }
        }

        // usersテーブルが空なら初期ユーザー(admin)を追加
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM users")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                String passwordHash = encoder.encode("admin123");
                int adminRoleId;
                try (PreparedStatement roleStmt = conn.prepareStatement("SELECT id FROM roles WHERE role_name = 'administrator'")) {
                    ResultSet roleRs = roleStmt.executeQuery();
                    if (roleRs.next()) {
                        adminRoleId = roleRs.getInt("id");
                    } else {
                        throw new SQLException("管理者ロールが見つかりません");
                    }
                }
                try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO users (username, email, password_hash, role_id) VALUES (?, ?, ?, ?)")) {
                    insertStmt.setString(1, "admin");
                    insertStmt.setString(2, "admin@example.com");
                    insertStmt.setString(3, passwordHash);
                    insertStmt.setInt(4, adminRoleId);
                    insertStmt.executeUpdate();
                    log.accept("初期ユーザー(admin)を管理者ロールで作成しました", "INFO");
                }
            }
        }

        // action_toolsテーブルが空なら初期アクションツールを追加
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM action_tools")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                String[][] initialTools = {
                    {"mail_alert", "mail", "true", "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[SECURITY ALERT] {attack_type} detected from {ip_address}\",\"body_template\":\"Security Alert\\n\\nServer: {server_name}\\nAttack Type: {attack_type}\\nSource IP: {ip_address}\\nURL: {url}\\nTime: {timestamp}\"}", "メール通知ツール - セキュリティアラートをメールで送信"},
                    {"iptables_block", "iptables", "false", "{\"action\":\"DROP\",\"chain\":\"INPUT\",\"comment\":\"Blocked by Edamame Security Analyzer\",\"timeout_minutes\":60}", "iptables連携ツール - 攻撃元IPをファイアウォールでブロック"},
                    {"cloudflare_block", "cloudflare", "false", "{\"api_token\":\"\",\"zone_id\":\"\",\"block_mode\":\"challenge\",\"notes\":\"Blocked by Edamame Security Analyzer\"}", "Cloudflare連携ツール - 攻撃元IPをCloudflareでブロック"},
                    {"webhook_notify", "webhook", "false", "{\"url\":\"\",\"method\":\"POST\",\"headers\":{\"Content-Type\":\"application/json\"},\"payload_template\":\"{\\\"server\\\":\\\"{server_name}\\\",\\\"attack_type\\\":\\\"{attack_type}\\\",\\\"ip_address\\\":\\\"{ip_address}\\\",\\\"url\\\":\\\"{url}\\\",\\\"timestamp\\\":\\\"{timestamp}\\\"}\"}", "Webhook通知ツール - カスタムWebhookエンドポイントに通知"},
                    {"daily_report_mail", "mail", "false", "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[日次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"日次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}", "日次レポートメール送信ツール"},
                    {"weekly_report_mail", "mail", "false", "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[週次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"週次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}", "週次レポートメール送信ツール"},
                    {"monthly_report_mail", "mail", "false", "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[月次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"月次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}", "月次レポートメール送信ツール"}
                };
                try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO action_tools (tool_name, tool_type, is_enabled, config_json, description) VALUES (?, ?, ?, ?, ?)")) {
                    for (String[] tool : initialTools) {
                        insertStmt.setString(1, tool[0]);
                        insertStmt.setString(2, tool[1]);
                        insertStmt.setBoolean(3, Boolean.parseBoolean(tool[2]));
                        insertStmt.setString(4, tool[3]);
                        insertStmt.setString(5, tool[4]);
                        insertStmt.addBatch();
                    }
                    insertStmt.executeBatch();
                    log.accept("初期アクションツール（mail_alert, iptables_block, cloudflare_block, webhook_notify）を作成しました", "INFO");
                }
            }
        }

        // action_rulesテーブルが空なら初期ルールを追加
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM action_rules")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                int mailToolId = 0;
                try (PreparedStatement toolStmt = conn.prepareStatement("SELECT id FROM action_tools WHERE tool_name = 'mail_alert'")) {
                    ResultSet toolRs = toolStmt.executeQuery();
                    if (toolRs.next()) {
                        mailToolId = toolRs.getInt("id");
                    }
                }
                if (mailToolId > 0) {
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                        "INSERT INTO action_rules (rule_name, target_server, condition_type, condition_params, action_tool_id, action_params, is_enabled, priority) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                        insertStmt.setString(1, "攻撃検知時メール通知");
                        insertStmt.setString(2, "*");
                        insertStmt.setString(3, "attack_detected");
                        insertStmt.setString(4, "{\"attack_types\":[\"SQL_INJECTION\",\"XSS\",\"COMMAND_INJECTION\",\"LFI\",\"RFI\",\"XXE\"]}");
                        insertStmt.setInt(5, mailToolId);
                        insertStmt.setString(6, "{\"severity\":\"high\",\"immediate\":true}");
                        insertStmt.setBoolean(7, true);
                        insertStmt.setInt(8, 10);
                        insertStmt.executeUpdate();
                        log.accept("初期アクションルール（攻撃検知時メール通知）を作成しました", "INFO");
                    }
                }
            }
        }
    }
}
