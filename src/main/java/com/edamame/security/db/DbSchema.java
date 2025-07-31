package com.edamame.security.db;

import java.sql.*;
import java.util.function.BiConsumer;

/**
 * DBスキーマ管理クラス
 * DBの初期テーブル構造作成・カラム存在確認・追加機能を提供
 */
public class DbSchema {


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
     * 指定テーブルのカラム一覧を取得
     * @param conn データベース接続
     * @param tableName テーブル名
     * @return カラム名のセット
     * @throws SQLException SQL例外
     */
    private static java.util.Set<String> getTableColumns(Connection conn, String tableName) throws SQLException {
        java.util.Set<String> columns = new java.util.HashSet<>();
        try (PreparedStatement pstmt = conn.prepareStatement("SHOW COLUMNS FROM " + tableName)) {
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                columns.add(rs.getString("Field"));
            }
        }
        return columns;
    }

    /**
     * テーブルのカラム構造を理想定義と比較し、追加・削除・移行対象を判定
     * @param existingColumns 現在のカラムセット
     * @param idealColumns 理想カラムセット
     * @return 比較結果（追加・削除・一致）
     */
    private static java.util.Map<String, java.util.Set<String>> compareTableColumns(java.util.Set<String> existingColumns, java.util.Set<String> idealColumns) {
        java.util.Set<String> toAdd = new java.util.HashSet<>(idealColumns);
        toAdd.removeAll(existingColumns);
        java.util.Set<String> toDelete = new java.util.HashSet<>(existingColumns);
        toDelete.removeAll(idealColumns);
        java.util.Set<String> matched = new java.util.HashSet<>(existingColumns);
        matched.retainAll(idealColumns);
        java.util.Map<String, java.util.Set<String>> result = new java.util.HashMap<>();
        result.put("add", toAdd);
        result.put("delete", toDelete);
        result.put("matched", matched);
        return result;
    }

    /**
     * テーブルに不足カラムを追加
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param columnsToAdd 追加するカラム名セット
     * @param columnDefs カラム定義マップ（カラム名→定義）
     * @param log ログ出力関数
     */
    private static void addMissingColumns(Connection conn, String tableName, java.util.Set<String> columnsToAdd, java.util.Map<String, String> columnDefs, BiConsumer<String, String> log) throws SQLException {
        for (String col : columnsToAdd) {
            if (columnDefs.containsKey(col)) {
                addColumn(conn, tableName, col, columnDefs.get(col));
                log.accept(tableName + "テーブルに" + col + "カラムを追加しました", "INFO");
            }
        }
    }

    /**
     * テーブルから不要カラムを削除
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param columnsToDelete 削除するカラム名セット
     * @param log ログ出力関数
     */
    private static void dropExtraColumns(Connection conn, String tableName, java.util.Set<String> columnsToDelete, BiConsumer<String, String> log) throws SQLException {
        for (String col : columnsToDelete) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE " + tableName + " DROP COLUMN " + col);
                log.accept(tableName + "テーブルから不要カラム " + col + " を削除しました", "INFO");
            }
        }
    }

    /**
     * カラムデータ移行（旧カラム→新カラム、移行元テーブル指定可）
     * @param conn データベース接続
     * @param toTable 移行先テーブル名
     * @param fromTable 移行元テーブル名
     * @param fromCol 移行元カラム
     * @param toCol 移行先カラム
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    private static void migrateColumnData(Connection conn, String toTable, String fromTable, String fromCol, String toCol, BiConsumer<String, String> log) throws SQLException {
        String sql;
        if (toTable.equals(fromTable)) {
            // 同一テーブル内移行
            sql = String.format("UPDATE %s SET %s = %s WHERE %s IS NULL OR %s = ''", toTable, toCol, fromCol, toCol, toCol);
        } else {
            // 別テーブルから移行（idで紐付け）
            sql = String.format("UPDATE %s t SET %s = (SELECT f.%s FROM %s f WHERE f.id = t.id) WHERE (%s IS NULL OR %s = '') AND EXISTS (SELECT 1 FROM %s f WHERE f.id = t.id)",
                toTable, toCol, fromCol, fromTable, toCol, toCol, fromTable);
        }
        try (Statement stmt = conn.createStatement()) {
            int updated = stmt.executeUpdate(sql);
            log.accept(toTable + "テーブル: " + fromTable + "." + fromCol + "→" + toCol + " へデータ移行（" + updated + "件）", "INFO");
        }
    }


    /**
     * 任意テーブルの理想カラム定義と現状を比較し、
     * 不足カラムの追加・不要カラムの削除・必要なデータ移行を自動実行する
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param idealColumnDefs 理想カラム定義（カラム名→定義）
     * @param migrateMap データ移行が必要な場合のマッピング（旧カラム→新カラム）
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    public static void autoSyncTableColumns(Connection conn, String tableName, java.util.Map<String, String> idealColumnDefs, java.util.Map<String, String> migrateMap, BiConsumer<String, String> log) throws SQLException {
        // ①テーブル存在チェック
        if (!tableExists(conn, tableName)) {
            // ②テーブルが存在しない場合は新規作成
            StringBuilder sb = new StringBuilder();
            sb.append("CREATE TABLE ").append(tableName).append(" (");
            int i = 0;
            for (var entry : idealColumnDefs.entrySet()) {
                if (i > 0) sb.append(", ");
                sb.append(entry.getKey()).append(" ").append(entry.getValue());
                i++;
            }
            sb.append(") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(sb.toString());
                log.accept(tableName + "テーブルを新規作成しました", "INFO");
            }
            return;
        }
        else {
            log.accept(tableName + "テーブルが既に存在します", "DEBUG");
        }
        // ③既存カラム一覧取得
        var existingColumns = getTableColumns(conn, tableName);
        var idealColumns = idealColumnDefs.keySet();
        // ④カラム差分判定（追加・削除）
        var diff = compareTableColumns(existingColumns, new java.util.HashSet<>(idealColumns));
        // ⑤不足カラム追加
        addMissingColumns(conn, tableName, diff.get("add"), idealColumnDefs, log);
        // ⑥カラム移行（必要な場合のみ）
        if (migrateMap != null) {
            for (var entry : migrateMap.entrySet()) {
                String fromCol = entry.getKey();
                String toCol = entry.getValue();
                if (existingColumns.contains(fromCol) && idealColumns.contains(toCol)) {
                    migrateColumnData(conn, tableName, tableName, fromCol, toCol, log);
                }
            }
        }
        // ⑦不要カラム削除
        dropExtraColumns(conn, tableName, diff.get("delete"), log);
    }

    /**
     * 主要全テーブルのスキーマ自動整合（カラム追加・削除・移行）を一括実行する
     * @param conn データベース接続
     * @param log ログ出力関数
     * @throws SQLException SQL例外
     */
    public static void syncAllTablesSchema(Connection conn, BiConsumer<String, String> log) throws SQLException {
        // access_log
        var accessLogDefs = new java.util.LinkedHashMap<String, String>();
        accessLogDefs.put("id", "BIGINT AUTO_INCREMENT PRIMARY KEY");
        accessLogDefs.put("server_name", "VARCHAR(100) NOT NULL DEFAULT 'default'");
        accessLogDefs.put("method", "VARCHAR(10) NOT NULL");
        accessLogDefs.put("full_url", "TEXT NOT NULL");
        accessLogDefs.put("status_code", "INT NOT NULL");
        accessLogDefs.put("ip_address", "VARCHAR(45) NOT NULL");
        accessLogDefs.put("access_time", "DATETIME NOT NULL");
        accessLogDefs.put("blocked_by_modsec", "BOOLEAN DEFAULT FALSE");
        accessLogDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        autoSyncTableColumns(conn, "access_log", accessLogDefs, null, log);

        // url_registry
        var urlRegistryDefs = new java.util.LinkedHashMap<String, String>();
        urlRegistryDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        urlRegistryDefs.put("server_name", "VARCHAR(100) NOT NULL DEFAULT 'default'");
        urlRegistryDefs.put("method", "VARCHAR(10) NOT NULL");
        urlRegistryDefs.put("full_url", "TEXT NOT NULL");
        urlRegistryDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        urlRegistryDefs.put("updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        urlRegistryDefs.put("is_whitelisted", "BOOLEAN DEFAULT FALSE");
        urlRegistryDefs.put("attack_type", "VARCHAR(50) DEFAULT 'none'");
        urlRegistryDefs.put("user_final_threat", "BOOLEAN DEFAULT NULL");
        urlRegistryDefs.put("user_threat_note", "TEXT");
        autoSyncTableColumns(conn, "url_registry", urlRegistryDefs, null, log);

        // modsec_alerts
        var modsecDefs = new java.util.LinkedHashMap<String, String>();
        modsecDefs.put("id", "BIGINT AUTO_INCREMENT PRIMARY KEY");
        modsecDefs.put("access_log_id", "BIGINT NOT NULL");
        modsecDefs.put("rule_id", "VARCHAR(20)");
        modsecDefs.put("severity", "VARCHAR(20)");
        modsecDefs.put("message", "TEXT");
        modsecDefs.put("data_value", "TEXT");
        modsecDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        modsecDefs.put("detected_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        modsecDefs.put("server_name", "VARCHAR(100) NOT NULL DEFAULT 'default'");
        // 旧カラム名からの移行
        var modsecMigrate = new java.util.HashMap<String, String>();
        modsecMigrate.put("msg", "message");
        modsecMigrate.put("data", "data_value");
        autoSyncTableColumns(conn, "modsec_alerts", modsecDefs, modsecMigrate, log);

        // servers
        var serversDefs = new java.util.LinkedHashMap<String, String>();
        serversDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        serversDefs.put("server_name", "VARCHAR(100) NOT NULL UNIQUE COLLATE utf8mb4_unicode_ci");
        serversDefs.put("server_description", "TEXT COLLATE utf8mb4_unicode_ci");
        serversDefs.put("log_path", "VARCHAR(500) COLLATE utf8mb4_unicode_ci DEFAULT ''");
        serversDefs.put("is_active", "BOOLEAN DEFAULT TRUE");
        serversDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        serversDefs.put("updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        serversDefs.put("last_log_received", "DATETIME");
        // 旧カラム名からの移行
        var serversMigrate = new java.util.HashMap<String, String>();
        serversMigrate.put("description", "server_description");
        serversMigrate.put("last_activity_at", "last_log_received");
        autoSyncTableColumns(conn, "servers", serversDefs, serversMigrate, log);

        // settings
        var settingsDefs = new java.util.LinkedHashMap<String, String>();
        settingsDefs.put("id", "INT PRIMARY KEY");
        settingsDefs.put("whitelist_mode", "BOOLEAN DEFAULT FALSE");
        settingsDefs.put("whitelist_ip", "VARCHAR(45) DEFAULT ''");
        settingsDefs.put("backend_version", "VARCHAR(50) DEFAULT ''");
        settingsDefs.put("frontend_version", "VARCHAR(50) DEFAULT ''");
        settingsDefs.put("access_log_retention_days", "INT DEFAULT NULL");
        settingsDefs.put("login_history_retention_days", "INT DEFAULT NULL");
        settingsDefs.put("action_execution_log_retention_days", "INT DEFAULT NULL");
        autoSyncTableColumns(conn, "settings", settingsDefs, null, log);

        // users
        var usersDefs = new java.util.LinkedHashMap<String, String>();
        usersDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        usersDefs.put("username", "VARCHAR(50) NOT NULL UNIQUE");
        usersDefs.put("email", "VARCHAR(255) NOT NULL DEFAULT ''");
        usersDefs.put("password_hash", "VARCHAR(255) NOT NULL");
        usersDefs.put("role_id", "INT NOT NULL");
        usersDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        usersDefs.put("updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        usersDefs.put("is_active", "BOOLEAN DEFAULT TRUE");
        autoSyncTableColumns(conn, "users", usersDefs, null, log);

        // roles
        var rolesDefs = new java.util.LinkedHashMap<String, String>();
        rolesDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        rolesDefs.put("role_name", "VARCHAR(50) NOT NULL UNIQUE");
        rolesDefs.put("description", "TEXT");
        rolesDefs.put("created_at", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        rolesDefs.put("updated_at", "DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
        autoSyncTableColumns(conn, "roles", rolesDefs, null, log);

        // login_history
        var loginHistoryDefs = new java.util.LinkedHashMap<String, String>();
        loginHistoryDefs.put("id", "BIGINT AUTO_INCREMENT PRIMARY KEY");
        loginHistoryDefs.put("user_id", "INT NOT NULL");
        loginHistoryDefs.put("login_time", "DATETIME DEFAULT CURRENT_TIMESTAMP");
        loginHistoryDefs.put("ip_address", "VARCHAR(45) NOT NULL");
        loginHistoryDefs.put("user_agent", "TEXT");
        loginHistoryDefs.put("success", "BOOLEAN NOT NULL DEFAULT TRUE");
        autoSyncTableColumns(conn, "login_history", loginHistoryDefs, null, log);

        // sessions
        var sessionsDefs = new java.util.LinkedHashMap<String, String>();
        sessionsDefs.put("session_id", "VARCHAR(64) PRIMARY KEY");
        sessionsDefs.put("username", "VARCHAR(255) NOT NULL");
        sessionsDefs.put("expires_at", "DATETIME NOT NULL");
        sessionsDefs.put("created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        autoSyncTableColumns(conn, "sessions", sessionsDefs, null, log);

        // action_execution_log
        var actionExecutionLogDefs = new java.util.LinkedHashMap<String, String>();
        actionExecutionLogDefs.put("id", "BIGINT AUTO_INCREMENT PRIMARY KEY");
        actionExecutionLogDefs.put("action_rule_id", "INT NOT NULL");
        actionExecutionLogDefs.put("action_tool_id", "INT NOT NULL");
        actionExecutionLogDefs.put("target_server", "VARCHAR(100) NOT NULL");
        actionExecutionLogDefs.put("executed_at", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        actionExecutionLogDefs.put("status", "VARCHAR(20) NOT NULL DEFAULT 'pending'");
        actionExecutionLogDefs.put("result_message", "TEXT");
        actionExecutionLogDefs.put("params_json", "TEXT");
        autoSyncTableColumns(conn, "action_execution_log", actionExecutionLogDefs, null, log);
    }
}
