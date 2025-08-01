package com.edamame.security;

import com.edamame.web.security.BCryptPasswordEncoder;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * DBスキーマ管理クラス
 * DBの初期テーブル構造作成・カラム存在確認・追加機能を提供
 */
public class DbSchema {

    // ModSecurityアラート詳細保存用テーブル名
    private static final String MODSEC_ALERT_TABLE = "modsec_alerts";

    /**
     * DB初期化処理。テーブル・カラムの存在確認と作成。
     * usersテーブル新規作成時は初期ユーザー(admin)を自動追加する。
     * @param conn MySQLコネクション
     * @param appVersion バックエンドバージョン
     * @param logFunc ログ出力用関数（省略可）
     * @return 初期化成功可否
     */
    public static boolean createInitialTables(Connection conn, String appVersion, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            // 自動コミットを無効にして手動トランザクション管理
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {

                // settingsテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS settings (
                        id INT PRIMARY KEY,
                        whitelist_mode BOOLEAN DEFAULT FALSE,
                        whitelist_ip VARCHAR(45) DEFAULT '',
                        backend_version VARCHAR(50) DEFAULT '',
                        frontend_version VARCHAR(50) DEFAULT ''
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("settingsテーブルを作成または確認しました", "INFO");

                // rolesテーブル（usersより先に作成）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS roles (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        role_name VARCHAR(50) NOT NULL UNIQUE,
                        description TEXT,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("rolesテーブルを作成または確認しました", "INFO");

                // url_registryテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS url_registry (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        server_name VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'URL発見元サーバー名',
                        method VARCHAR(10) NOT NULL,
                        full_url TEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        is_whitelisted BOOLEAN DEFAULT FALSE,
                        attack_type VARCHAR(50) DEFAULT 'none',
                        user_final_threat BOOLEAN DEFAULT NULL,
                        user_threat_note TEXT
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("url_registryテーブルを作成または確認しました", "INFO");

                // access_logテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS access_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_name VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'ログ送信元サーバー名',
                        method VARCHAR(10) NOT NULL,
                        full_url TEXT NOT NULL,
                        status_code INT NOT NULL,
                        ip_address VARCHAR(45) NOT NULL,
                        access_time DATETIME NOT NULL,
                        blocked_by_modsec BOOLEAN DEFAULT FALSE,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("access_logテーブルを作成または確認しました", "INFO");

                // modsec_alertsテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS modsec_alerts (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        server_name VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'アラート発生元サーバー名',
                        access_log_id BIGINT NOT NULL,
                        rule_id VARCHAR(20),
                        severity VARCHAR(20),
                        message TEXT,
                        data_value TEXT,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        detected_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY (access_log_id) REFERENCES access_log(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("modsec_alertsテーブルを作成または確認しました", "INFO");

                // usersテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(50) NOT NULL UNIQUE,
                        password_hash VARCHAR(255) NOT NULL,
                        role_id INT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        last_login DATETIME NULL,
                        is_active BOOLEAN DEFAULT TRUE,
                        FOREIGN KEY (role_id) REFERENCES roles(id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("usersテーブルを作成または確認しました", "INFO");

                // login_historyテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS login_history (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        user_id INT NOT NULL,
                        login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        ip_address VARCHAR(45) NOT NULL,
                        user_agent TEXT,
                        success BOOLEAN NOT NULL DEFAULT TRUE,
                        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("login_historyテーブルを作成または確認しました", "INFO");

                // action_toolsテーブル（操作ツール管理）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS action_tools (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        tool_name VARCHAR(50) NOT NULL COMMENT '操作ツール名 (mail, iptables, cloudflare)',
                        tool_type ENUM('mail', 'iptables', 'cloudflare', 'webhook', 'custom') NOT NULL COMMENT 'ツールタイプ',
                        is_enabled BOOLEAN DEFAULT TRUE COMMENT 'ツール有効化フラグ',
                        config_json JSON COMMENT 'ツール固有設定 (JSON形式)',
                        description TEXT COMMENT 'ツールの説明',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY unique_tool_name (tool_name)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("action_toolsテーブルを作成または確認しました", "INFO");

                // action_rulesテーブル（アクション実行ルール管理）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS action_rules (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        rule_name VARCHAR(100) NOT NULL COMMENT 'ルール名',
                        target_server VARCHAR(100) DEFAULT '*' COMMENT '対象サーバー名 (* = 全サーバー)',
                        condition_type ENUM('attack_detected', 'ip_frequency', 'status_code', 'scheduled_report', 'custom') NOT NULL COMMENT '実行条件タイプ',
                        condition_params JSON COMMENT '条件パラメータ (JSON形式)',
                        action_tool_id INT NOT NULL COMMENT '実行する操作ツールID',
                        action_params JSON COMMENT 'アクション固有パラメータ (JSON形式)',
                        is_enabled BOOLEAN DEFAULT TRUE COMMENT 'ルール有効化フラグ',
                        priority INT DEFAULT 100 COMMENT 'ルール優先度 (数値が小さいほど高優先)',
                        last_executed DATETIME NULL COMMENT '最終実行日時',
                        execution_count INT DEFAULT 0 COMMENT '実行回数',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        FOREIGN KEY (action_tool_id) REFERENCES action_tools(id) ON DELETE CASCADE,
                        INDEX idx_target_server (target_server),
                        INDEX idx_condition_type (condition_type),
                        INDEX idx_enabled (is_enabled),
                        INDEX idx_priority (priority)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("action_rulesテーブルを作成または確認しました", "INFO");

                // action_execution_logテーブル（アクション実行履歴）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS action_execution_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        rule_id INT NOT NULL COMMENT '実行されたルールID',
                        server_name VARCHAR(100) NOT NULL COMMENT 'アクション実行対象サーバー',
                        trigger_event JSON COMMENT 'トリガーとなったイベント情報',
                        execution_status ENUM('success', 'failed', 'skipped') NOT NULL COMMENT '実行ステータス',
                        execution_result TEXT COMMENT '実行結果・エラーメッセージ',
                        execution_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '実行日時',
                        processing_duration_ms INT DEFAULT 0 COMMENT '処理時間（ミリ秒）',
                        FOREIGN KEY (rule_id) REFERENCES action_rules(id) ON DELETE CASCADE,
                        INDEX idx_rule_id (rule_id),
                        INDEX idx_server_name (server_name),
                        INDEX idx_execution_time (execution_time),
                        INDEX idx_execution_status (execution_status)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("action_execution_logテーブルを作成または確認しました", "INFO");

                // serversテーブル（複数サーバー管理用）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS servers (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        server_name VARCHAR(100) NOT NULL UNIQUE COLLATE utf8mb4_unicode_ci COMMENT 'サーバー識別名',
                        server_description TEXT COLLATE utf8mb4_unicode_ci COMMENT 'サーバー説明',
                        log_path VARCHAR(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'ログファイルパス',
                        is_active BOOLEAN DEFAULT TRUE COMMENT 'アクティブ状態',
                        last_log_received DATETIME DEFAULT NULL COMMENT '最終ログ受信時刻',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("serversテーブルを作成または確認しました（照合順序統一）", "INFO");

                // 既存テーブルの照合順序を修正
                fixCollationIssues(stmt, log);

                // serversテーブルにインデックスを追加
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_servers_name ON servers(server_name)");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_servers_active ON servers(is_active)");
                } catch (SQLException e) {
                    // インデックスが既に存在する場合は無視
                    log.accept("serversテーブルインデックス設定済み: " + e.getMessage(), "DEBUG");
                }

                // 手動コミット
                conn.commit();

                // 元の自動コミット設定に戻す
                conn.setAutoCommit(originalAutoCommit);

                // テーブル構造の更新チェック
                updateTableStructure(conn, log);

                // 初期データの投入
                initializeDefaultData(conn, appVersion, log);

                return true;

            } catch (SQLException e) {
                // エラー時はロールバック
                conn.rollback();
                conn.setAutoCommit(originalAutoCommit);
                log.accept("DB初期化でエラー: " + e.getMessage(), "ERROR");
                throw e;
            }

        } catch (SQLException e) {
            log.accept("テーブル作成でエラー: " + e.getMessage(), "CRITICAL");
            return false;
        }
    }

    /**
     * 既存テーブルの構造を最新版に更新
     * @param conn データベース接続
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    private static void updateTableStructure(Connection conn, BiConsumer<String, String> log) throws SQLException {
        log.accept("テーブル構造の更新チェックを開始します", "INFO");

        // modsec_alertsテーブルのdetected_atカラムを確認・追加
        if (!columnExists(conn, "modsec_alerts", "detected_at")) {
            addColumn(conn, "modsec_alerts", "detected_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
            log.accept("modsec_alertsテーブルにdetected_atカラムを追加しました", "INFO");
        }

        // 他の必要なカラムの確認・追加
        ensureAllRequiredColumns(conn, log);
    }

    /**
     * 初期データを挿入する
     * @param conn データベース接続
     * @param appVersion アプリケーションバージョン
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    private static void initializeDefaultData(Connection conn, String appVersion, BiConsumer<String, String> log) throws SQLException {

        // settingsテーブルが空なら初期レコード挿入
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM settings")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO settings (id, whitelist_mode, whitelist_ip, backend_version, frontend_version) VALUES (?, ?, ?, ?, ?)")) {
                    insertStmt.setInt(1, 1);
                    insertStmt.setBoolean(2, false);
                    insertStmt.setString(3, "");
                    insertStmt.setString(4, appVersion);
                    insertStmt.setString(5, "");
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
                // デフォルトパスワード 'admin123' をハッシュ化
                BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
                String passwordHash = encoder.encode("admin123");

                // 管理者ロールのIDを取得
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
                    "INSERT INTO users (username, password_hash, role_id) VALUES (?, ?, ?)")) {
                    insertStmt.setString(1, "admin");
                    insertStmt.setString(2, passwordHash);
                    insertStmt.setInt(3, adminRoleId);
                    insertStmt.executeUpdate();
                    log.accept("初期ユーザー(admin)を管理者ロールで作成しました", "INFO");
                }
            }
        }

        // action_toolsテーブルが空なら初期アクションツールを追加
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM action_tools")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                // 初期アクションツールの定義
                String[][] initialTools = {
                    {
                        "mail_alert",
                        "mail",
                        "true",
                        "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[SECURITY ALERT] {attack_type} detected from {ip_address}\",\"body_template\":\"Security Alert\\n\\nServer: {server_name}\\nAttack Type: {attack_type}\\nSource IP: {ip_address}\\nURL: {url}\\nTime: {timestamp}\"}",
                        "メール通知ツール - セキュリティアラートをメールで送信"
                    },
                    {
                        "iptables_block",
                        "iptables",
                        "false",
                        "{\"action\":\"DROP\",\"chain\":\"INPUT\",\"comment\":\"Blocked by Edamame Security Analyzer\",\"timeout_minutes\":60}",
                        "iptables連携ツール - 攻撃元IPをファイアウォールでブロック"
                    },
                    {
                        "cloudflare_block",
                        "cloudflare",
                        "false",
                        "{\"api_token\":\"\",\"zone_id\":\"\",\"block_mode\":\"challenge\",\"notes\":\"Blocked by Edamame Security Analyzer\"}",
                        "Cloudflare連携ツール - 攻撃元IPをCloudflareでブロック"
                    },
                    {
                        "webhook_notify",
                        "webhook",
                        "false",
                        "{\"url\":\"\",\"method\":\"POST\",\"headers\":{\"Content-Type\":\"application/json\"},\"payload_template\":\"{\\\"server\\\":\\\"{server_name}\\\",\\\"attack_type\\\":\\\"{attack_type}\\\",\\\"ip_address\\\":\\\"{ip_address}\\\",\\\"url\\\":\\\"{url}\\\",\\\"timestamp\\\":\\\"{timestamp}\\\"}\"}",
                        "Webhook通知ツール - カスタムWebhookエンドポイントに通知"
                    },
                    {
                        "daily_report_mail",
                        "mail",
                        "false",
                        "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[日次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"日次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}",
                        "日次レポートメール送信ツール"
                    },
                    {
                        "weekly_report_mail",
                        "mail",
                        "false",
                        "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[週次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"週次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}",
                        "週次レポートメール送信ツール"
                    },
                    {
                        "monthly_report_mail",
                        "mail",
                        "false",
                        "{\"to_email\":\"admin@example.com\",\"subject_template\":\"[月次レポート] {server_name} セキュリティ統計 ({start_time}～{end_time})\",\"body_template\":\"月次セキュリティレポート\\n\\nサーバー: {server_name}\\n期間: {start_time} ～ {end_time}\\n\\n=== アクセス統計 ===\\n総アクセス数: {total_access}\\n\\n=== 攻撃統計 ===\\n検知された攻撃数: {total_attacks}\\n攻撃タイプ別: {attack_types}\\n\\n=== ModSecurity統計 ===\\nブロック数: {modsec_blocked}\\n上位ルール: {top_modsec_rules}\\n\\n=== URL統計 ===\\n新規URL発見数: {new_urls}\"}",
                        "月次レポートメール送信ツール"
                    }
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
                // mail_alertツールのIDを取得
                int mailToolId = 0;
                try (PreparedStatement toolStmt = conn.prepareStatement("SELECT id FROM action_tools WHERE tool_name = 'mail_alert'")) {
                    ResultSet toolRs = toolStmt.executeQuery();
                    if (toolRs.next()) {
                        mailToolId = toolRs.getInt("id");
                    }
                }

                if (mailToolId > 0) {
                    // 初期ルールの定義（攻撃検知時のメール通知）
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

    /**
     * 主要テーブルのカラム存在確認・不足カラム自動追加
     * @param conn MySQLコネクション
     * @param logFunc ログ出力用関数（省略可）
     * @return カラム確認・追加成功可否
     */
    public static boolean ensureAllRequiredColumns(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            // modsec_alertsテーブルのカラム構造を修正
            if (!ensureModsecAlertsColumns(conn, log)) {
                log.accept("modsec_alertsテーブルのカラム構造修正に失敗しました", "ERROR");
                return false;
            }

            // url_registryテーブルの必要カラム確認・追加
            String[][] urlRegistryColumns = {
                {"user_final_threat", "BOOLEAN DEFAULT NULL"},
                {"user_threat_note", "TEXT"}
            };

            for (String[] column : urlRegistryColumns) {
                if (!columnExists(conn, "url_registry", column[0])) {
                    addColumn(conn, "url_registry", column[0], column[1]);
                    log.accept("url_registryテーブルに" + column[0] + "カラムを追加しました", "INFO");
                }
            }

            // settingsテーブルの必要カラム確認・追加
            String[][] settingsColumns = {
                {"backend_version", "VARCHAR(50) DEFAULT ''"},
                {"frontend_version", "VARCHAR(50) DEFAULT ''"}
            };

            for (String[] column : settingsColumns) {
                if (!columnExists(conn, "settings", column[0])) {
                    addColumn(conn, "settings", column[0], column[1]);
                    log.accept("settingsテーブルに" + column[0] + "カラムを追加しました", "INFO");
                }
            }

            // serversテーブルの必要カラム確認・追加
            String[][] serversColumns = {
                {"server_description", "TEXT COMMENT 'サーバーの説明'"},
                {"log_path", "VARCHAR(500) COMMENT 'ログファイルパス'"},
                {"last_log_received", "DATETIME COMMENT '最終ログ受信日時'"},
                {"is_active", "BOOLEAN DEFAULT TRUE COMMENT '有効フラグ'"},
                {"created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時'"},
                {"updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最終更新日時'"}
            };

            for (String[] column : serversColumns) {
                if (!columnExists(conn, "servers", column[0])) {
                    addColumn(conn, "servers", column[0], column[1]);
                    log.accept("serversテーブルに" + column[0] + "カラムを追加しました", "INFO");
                }
            }

            return true;

        } catch (SQLException e) {
            log.accept("カラム確認・追加でエラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * modsec_alertsテーブルのカラム構造を確認・修正
     * @param conn データベース接続
     * @param log ログ出力関数
     * @return 成功した場合true
     * @throws SQLException SQL例外
     */
    private static boolean ensureModsecAlertsColumns(Connection conn, BiConsumer<String, String> log) throws SQLException {
        // 既存のカラム構造をチェック
        boolean hasMessage = columnExists(conn, MODSEC_ALERT_TABLE, "message");
        boolean hasDataValue = columnExists(conn, MODSEC_ALERT_TABLE, "data_value");
        boolean hasOldMsg = columnExists(conn, MODSEC_ALERT_TABLE, "msg");
        boolean hasOldData = columnExists(conn, MODSEC_ALERT_TABLE, "data");

        // 古いカラム構造の場合は新しい構造に移行
        if (!hasMessage && hasOldMsg) {
            // msgカラムをmessageにリネーム
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + MODSEC_ALERT_TABLE + " CHANGE COLUMN msg message TEXT");
                log.accept("modsec_alertsテーブル: msgカラムをmessageにリネームしました", "INFO");
            }
        } else if (!hasMessage) {
            // messageカラムを新規追加
            addColumn(conn, MODSEC_ALERT_TABLE, "message", "TEXT");
            log.accept("modsec_alertsテーブルにmessageカラムを追加しました", "INFO");
        }

        if (!hasDataValue && hasOldData) {
            // dataカラムをdata_valueにリネーム
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + MODSEC_ALERT_TABLE + " CHANGE COLUMN data data_value TEXT");
                log.accept("modsec_alertsテーブル: dataカラムをdata_valueにリネームしました", "INFO");
            }
        } else if (!hasDataValue) {
            // data_valueカラムを新規追加
            addColumn(conn, MODSEC_ALERT_TABLE, "data_value", "TEXT");
            log.accept("modsec_alertsテーブルにdata_valueカラムを追加しました", "INFO");
        }

        // detected_atカラムの確認・追加（既存機能を維持）
        if (!columnExists(conn, MODSEC_ALERT_TABLE, "detected_at")) {
            addColumn(conn, MODSEC_ALERT_TABLE, "detected_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
            log.accept("modsec_alertsテーブルにdetected_atカラムを追加しました", "INFO");
        }

        return true;
    }

    /**
     * カラムが存在するかチェック
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param columnName カラム名
     * @return カラムが存在する場合true
     * @throws SQLException SQL例外
     */
    private static boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SHOW COLUMNS FROM " + tableName + " LIKE ?")) {
            pstmt.setString(1, columnName);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        }
    }

    /**
     * テーブルが存在するかチェック
     * @param conn データベース接続
     * @param tableName テーブル名
     * @return テーブルが存在する場合true
     * @throws SQLException SQL例外
     */
    private static boolean tableExists(Connection conn, String tableName) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("SHOW TABLES LIKE ?")) {
            pstmt.setString(1, tableName);
            ResultSet rs = pstmt.executeQuery();
            return rs.next();
        }
    }

    /**
     * テーブルにカラムを追加
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param columnName カラム名
     * @param columnDef カラム定義
     * @throws SQLException SQL例外
     */
    private static void addColumn(Connection conn, String tableName, String columnName, String columnDef) throws SQLException {
        String sql = String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnDef);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * 非推奨のsettingsカラムを削除
     * @param conn MySQLコネクション
     * @param logFunc ログ出力用関数（省略可）
     * @return 削除処理成功可否
     */
    @SuppressWarnings("unused")
    public static boolean dropDeprecatedSettingsColumns(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            // 非推奨カラムのリスト
            String[] deprecatedColumns = {
                "deprecated_column1",
                "deprecated_column2"
                // 将来的に削除予定のカラムがあればここに追加
            };

            for (String columnName : deprecatedColumns) {
                if (columnExists(conn, "settings", columnName)) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE settings DROP COLUMN " + columnName);
                        log.accept("settingsテーブルから非推奨カラム " + columnName + " を削除しました", "INFO");
                    }
                }
            }

            return true;

        } catch (SQLException e) {
            log.accept("非推奨カラム削除でエラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * データベースのテーブル統計情報を取得
     * @param conn データベース接続
     * @return 統計情報の文字列
     */
    @SuppressWarnings("unused")
    public static String getDatabaseStatistics(Connection conn) {
        StringBuilder stats = new StringBuilder();

        String[] tables = {"access_log", "url_registry", "modsec_alerts", "users", "login_history"};

        try {
            for (String tableName : tables) {
                try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM " + tableName)) {
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        stats.append(String.format("%s: %d件, ", tableName, rs.getInt(1)));
                    }
                }
            }
        } catch (SQLException e) {
            stats.append("統計情報取得エラー: ").append(e.getMessage());
        }

        return stats.toString();
    }

    /**
     * 複数サーバー対応のデータベーススキーマ拡張
     * access_log, url_registry, modsec_alertsテーブルにserver_nameカラムを追加し、
     * サーバー管理用のserversテーブルを作成する
     * @param conn MySQLコネクション
     * @param logFunc ログ出力用関数（省略可）
     * @return スキーマ拡張成功可否
     */
    public static boolean addMultiServerSupport(Connection conn, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        try {
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try (Statement stmt = conn.createStatement()) {

                // access_logテーブルにserver_nameカラムを追加
                if (!columnExists(conn, "access_log", "server_name")) {
                    stmt.execute("""
                        ALTER TABLE access_log
                        ADD COLUMN server_name VARCHAR(100) NOT NULL DEFAULT 'default'
                        COMMENT 'ログ送信元サーバー名'
                        """);
                    log.accept("access_logテーブルにserver_nameカラムを追加しました", "INFO");

                    // インデックスを追加（パフォーマンス向上のため）
                    stmt.execute("""
                        CREATE INDEX idx_access_log_server_name ON access_log(server_name)
                        """);
                    log.accept("access_logテーブルにserver_nameインデックスを追加しました", "INFO");
                }

                // url_registryテーブルにserver_nameカラムを追加
                if (!columnExists(conn, "url_registry", "server_name")) {
                    stmt.execute("""
                        ALTER TABLE url_registry
                        ADD COLUMN server_name VARCHAR(100) NOT NULL DEFAULT 'default'
                        COMMENT 'URL発見元サーバー名'
                        """);
                    log.accept("url_registryテーブルにserver_nameカラムを追加しました", "INFO");

                    // 複合インデックスを追加（method + full_url + server_nameの一意性確保）
                    stmt.execute("""
                        CREATE UNIQUE INDEX idx_url_registry_unique_server ON url_registry(method, full_url(500), server_name)
                        """);
                    log.accept("url_registryテーブルに複合インデックスを追加しました", "INFO");
                }

                // modsec_alertsテーブルにserver_nameカラムを追加
                if (!columnExists(conn, "modsec_alerts", "server_name")) {
                    stmt.execute("""
                        ALTER TABLE modsec_alerts
                        ADD COLUMN server_name VARCHAR(100) NOT NULL DEFAULT 'default'
                        COMMENT 'アラート発生元サーバー名'
                        """);
                    log.accept("modsec_alertsテーブルにserver_nameカラムを追加しました", "INFO");

                    // インデックスを追加
                    stmt.execute("""
                        CREATE INDEX idx_modsec_alerts_server_name ON modsec_alerts(server_name)
                        """);
                    log.accept("modsec_alertsテーブルにserver_nameインデックスを追加しました", "INFO");
                }

                // serversテーブルを作成（存在しない場合）
                if (!tableExists(conn, "servers")) {
                    stmt.execute("""
                        CREATE TABLE servers (
                            id INT AUTO_INCREMENT PRIMARY KEY,
                            server_name VARCHAR(100) NOT NULL UNIQUE COLLATE utf8mb4_unicode_ci COMMENT 'サーバー識別名',
                            server_description TEXT COLLATE utf8mb4_unicode_ci COMMENT 'サーバー説明',
                            log_path VARCHAR(500) COLLATE utf8mb4_unicode_ci DEFAULT '' COMMENT 'ログファイルパス',
                            is_active BOOLEAN DEFAULT TRUE COMMENT 'アクティブ状態',
                            last_log_received DATETIME DEFAULT NULL COMMENT '最終ログ受信時刻',
                            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                        """);
                    log.accept("serversテーブルを作成しました", "INFO");
                }

                conn.commit();
                conn.setAutoCommit(originalAutoCommit);

                log.accept("複数サーバー対応のスキーマ拡張が完了しました", "INFO");
                return true;

            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(originalAutoCommit);
                log.accept("複数サーバー対応スキーマ拡張でエラー: " + e.getMessage(), "ERROR");
                return false;
            }

        } catch (SQLException e) {
            log.accept("複数サーバー対応処理エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * データベース全体の照���順序問題を修正
     * @param stmt SQLステートメント
     * @param log ログ出力関数
     */
    private static void fixCollationIssues(Statement stmt, BiConsumer<String, String> log) {
        try {
            // 主要テーブルの文字列カラムを utf8mb4_unicode_ci に統一
            String[] alterCommands = {
                // access_logテーブルの修正
                "ALTER TABLE access_log MODIFY COLUMN server_name VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default'",
                "ALTER TABLE access_log MODIFY COLUMN method VARCHAR(10) COLLATE utf8mb4_unicode_ci NOT NULL",
                "ALTER TABLE access_log MODIFY COLUMN ip_address VARCHAR(45) COLLATE utf8mb4_unicode_ci NOT NULL",

                // url_registryテーブルの修正
                "ALTER TABLE url_registry MODIFY COLUMN server_name VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default'",
                "ALTER TABLE url_registry MODIFY COLUMN method VARCHAR(10) COLLATE utf8mb4_unicode_ci NOT NULL",
                "ALTER TABLE url_registry MODIFY COLUMN attack_type VARCHAR(50) COLLATE utf8mb4_unicode_ci DEFAULT 'none'",

                // modsec_alertsテーブルの修正
                "ALTER TABLE modsec_alerts MODIFY COLUMN server_name VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'default'",
                "ALTER TABLE modsec_alerts MODIFY COLUMN rule_id VARCHAR(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL",
                "ALTER TABLE modsec_alerts MODIFY COLUMN severity VARCHAR(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL",
                "ALTER TABLE modsec_alerts MODIFY COLUMN matched_var VARCHAR(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL",
                "ALTER TABLE modsec_alerts MODIFY COLUMN matched_var_name VARCHAR(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL",

                // usersテーブルの修正
                "ALTER TABLE users MODIFY COLUMN username VARCHAR(100) COLLATE utf8mb4_unicode_ci NOT NULL",
                "ALTER TABLE users MODIFY COLUMN email VARCHAR(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL",

                // rolesテーブルの修正
                "ALTER TABLE roles MODIFY COLUMN role_name VARCHAR(50) COLLATE utf8mb4_unicode_ci NOT NULL",

                // settingsテーブルの修正
                "ALTER TABLE settings MODIFY COLUMN whitelist_ip VARCHAR(45) COLLATE utf8mb4_unicode_ci DEFAULT ''",
                "ALTER TABLE settings MODIFY COLUMN backend_version VARCHAR(50) COLLATE utf8mb4_unicode_ci DEFAULT ''",
                "ALTER TABLE settings MODIFY COLUMN frontend_version VARCHAR(50) COLLATE utf8mb4_unicode_ci DEFAULT ''"
            };

            for (String alterCmd : alterCommands) {
                try {
                    stmt.execute(alterCmd);
                } catch (SQLException e) {
                    // カラムが存在しない場合は無視
                    log.accept("照合順序修正スキップ（カラム未存在）: " + e.getMessage(), "DEBUG");
                }
            }

            log.accept("データベース照合順序をutf8mb4_unicode_ciに統一しました", "INFO");

        } catch (Exception e) {
            log.accept("照合順序修正中にエラー: " + e.getMessage(), "WARN");
        }
    }

    /**
     * サーバー情報を登録または更新（照合順序対応版）
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @param logFunc ログ出力関数
     * @return 成功可否
     */
    public static boolean registerOrUpdateServer(Connection conn, String serverName, String description, String logPath, BiConsumer<String, String> logFunc) {
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
                            return true;
                        }
                    }
                } else {
                    // サーバーが存在しない場合は新規登録
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
                            return true;
                        }
                    }
                }
            }

        } catch (SQLException e) {
            log.accept("サーバー登録・更新エラー: " + e.getMessage(), "ERROR");
        }

        return false;
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

                if (updated > 0) {
                    log.accept("サーバーの最終ログ受信時刻を更新: " + serverName, "DEBUG");
                } else {
                    // サーバーが存在しない場合は自動登録
                    registerOrUpdateServer(conn, serverName, "自動検出されたサーバー", "", log);
                }
            }

        } catch (SQLException e) {
            log.accept("最終ログ受信時刻更新エラー: " + e.getMessage(), "WARN");
        }
    }

    /**
     * 利用可能なカラムに基づいてINSERT/UPDATE SQL文を動的構築
     * @param conn データベース接続
     * @param log ログ出力関数
     * @return 動的に構築されたSQL文
     */
    private static String buildInsertOrUpdateSql(Connection conn, BiConsumer<String, String> log) {
        StringBuilder insertPart = new StringBuilder("INSERT INTO servers (server_name");
        StringBuilder valuesPart = new StringBuilder("VALUES (?");
        StringBuilder updatePart = new StringBuilder("ON DUPLICATE KEY UPDATE server_name = VALUES(server_name)");
        
        try {
            // 存在するカラムを順番にチェックして SQL を構築
            if (columnExists(conn, "servers", "server_description")) {
                insertPart.append(", server_description");
                valuesPart.append(", ?");
                updatePart.append(", server_description = VALUES(server_description)");
            }
            if (columnExists(conn, "servers", "log_path")) {
                insertPart.append(", log_path");
                valuesPart.append(", ?");
                updatePart.append(", log_path = VALUES(log_path)");
            }
            if (columnExists(conn, "servers", "last_log_received")) {
                insertPart.append(", last_log_received");
                valuesPart.append(", NOW()");
                updatePart.append(", last_log_received = NOW()");
            }
            if (columnExists(conn, "servers", "updated_at")) {
                updatePart.append(", updated_at = NOW()");
            }
        } catch (SQLException e) {
            log.accept("SQL構築中にエラー: " + e.getMessage(), "DEBUG");
        }
        
        insertPart.append(") ");
        valuesPart.append(") ");
        
        return insertPart.toString() + valuesPart.toString() + updatePart.toString();
    }
}
