package com.edamame.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.*;
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
                        method VARCHAR(10) NOT NULL,
                        full_url TEXT NOT NULL,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        is_whitelisted BOOLEAN DEFAULT FALSE,
                        attack_type VARCHAR(50) DEFAULT 'none'
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """);
                log.accept("url_registryテーブルを作成または確認しました", "INFO");

                // access_logテーブル
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS access_log (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
                        access_log_id BIGINT NOT NULL,
                        rule_id VARCHAR(20),
                        severity VARCHAR(20),
                        message TEXT,
                        data_value TEXT,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
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
     * 複数サーバー対応のためのカラム追加処理
     * access_log, url_registry, modsec_alertsテーブルにserver_nameカラムを追加
     * @param conn データベース接続
     * @param logFunc ログ出力関数
     * @return 更新成功可否
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
                    stmt.execute("CREATE INDEX idx_access_log_server_name ON access_log(server_name)");
                    log.accept("access_logテーブルのserver_nameにインデックスを作成しました", "INFO");
                } else {
                    log.accept("access_logテーブルのserver_nameカラムは既に存在します", "DEBUG");
                }

                // url_registryテーブルにserver_nameカラムを追加
                if (!columnExists(conn, "url_registry", "server_name")) {
                    stmt.execute("""
                        ALTER TABLE url_registry 
                        ADD COLUMN server_name VARCHAR(100) NOT NULL DEFAULT 'default' 
                        COMMENT 'URL発見元サーバー名'
                    """);
                    log.accept("url_registryテーブルにserver_nameカラムを追加しました", "INFO");
                    
                    // 複合インデックスを追加（method + full_url + server_nameで一意性確保）
                    stmt.execute("CREATE INDEX idx_url_registry_server ON url_registry(server_name, method, full_url(100))");
                    log.accept("url_registryテーブルのserver_name複合インデックスを作成しました", "INFO");
                } else {
                    log.accept("url_registryテーブルのserver_nameカラムは既に存在します", "DEBUG");
                }

                // modsec_alertsテーブルにserver_nameカラムを追加
                if (!columnExists(conn, "modsec_alerts", "server_name")) {
                    stmt.execute("""
                        ALTER TABLE modsec_alerts 
                        ADD COLUMN server_name VARCHAR(100) NOT NULL DEFAULT 'default' 
                        COMMENT 'ModSecurityアラート発生サーバー名'
                    """);
                    log.accept("modsec_alertsテーブルにserver_nameカラムを追加しました", "INFO");
                    
                    // インデックスを追加
                    stmt.execute("CREATE INDEX idx_modsec_alerts_server_name ON modsec_alerts(server_name)");
                    log.accept("modsec_alertsテーブルのserver_nameにインデックスを作成しました", "INFO");
                } else {
                    log.accept("modsec_alertsテーブルのserver_nameカラムは既に存在します", "DEBUG");
                }

                // servers管理テーブルを作成（サーバー情報の一元管理用）
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS servers (
                        id INT AUTO_INCREMENT PRIMARY KEY,
                        server_name VARCHAR(100) NOT NULL UNIQUE COMMENT 'サーバー管理名',
                        log_path VARCHAR(500) NOT NULL COMMENT 'ログファイルパス',
                        description TEXT COMMENT 'サーバー説明',
                        is_active BOOLEAN DEFAULT TRUE COMMENT '監視有効フラグ',
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '登録日時',
                        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最終更新日時',
                        last_activity_at DATETIME COMMENT '最終ログ受信日時'
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci 
                    COMMENT='監視対象サーバー管理テーブル'
                """);
                log.accept("serversテーブルを作成または確認しました", "INFO");

                conn.commit();
                conn.setAutoCommit(originalAutoCommit);
                
                log.accept("複数サーバー対応スキーマ更新が完了しました", "INFO");
                return true;

            } catch (SQLException e) {
                conn.rollback();
                conn.setAutoCommit(originalAutoCommit);
                log.accept("複数サーバー対応スキーマ更新に失敗しました: " + e.getMessage(), "ERROR");
                return false;
            }

        } catch (SQLException e) {
            log.accept("複数サーバー対応スキーマ更新でエラーが発生しました: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * サーバー情報をserversテーブルに登録または更新
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param logPath ログファイルパス
     * @param description サーバー説明（任意）
     * @param logFunc ログ出力関数
     * @return 登録成功可否
     */
    public static boolean registerServer(Connection conn, String serverName, String logPath, String description, BiConsumer<String, String> logFunc) {
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        String sql = """
            INSERT INTO servers (server_name, log_path, description, last_activity_at) 
            VALUES (?, ?, ?, NOW()) 
            ON DUPLICATE KEY UPDATE 
                log_path = VALUES(log_path),
                description = VALUES(description),
                is_active = TRUE,
                updated_at = CURRENT_TIMESTAMP,
                last_activity_at = NOW()
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, serverName);
            pstmt.setString(2, logPath);
            pstmt.setString(3, description != null ? description : "");

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                log.accept("サーバー情報を登録/更新しました: " + serverName + " → " + logPath, "INFO");
                return true;
            } else {
                log.accept("サーバー情報の登録/更新に失敗しました: " + serverName, "WARN");
                return false;
            }

        } catch (SQLException e) {
            log.accept("サーバー情報登録エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }
}
