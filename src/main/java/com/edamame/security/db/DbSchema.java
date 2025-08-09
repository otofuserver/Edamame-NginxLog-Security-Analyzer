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
        // ⑧型の大きさ・型違いを自動で修正（matchedカラムのみ）
        alterColumnTypeIfNeeded(conn, tableName, idealColumnDefs, diff.get("matched"), log);
    }

    /**
     * 指定カラムに関係する外部キー制約をすべて取得（参照元・参照先両方）
     */
    private static java.util.List<String[]> getRelatedForeignKeys(Connection conn, String tableName, String columnName) throws SQLException {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        String dbName = conn.getCatalog();
        // 参照先（このカラムが他テーブルから参照されている）
        String sql1 = "SELECT TABLE_NAME, CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME=? AND REFERENCED_COLUMN_NAME=?";
        try (PreparedStatement ps = conn.prepareStatement(sql1)) {
            ps.setString(1, dbName);
            ps.setString(2, tableName);
            ps.setString(3, columnName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        // 参照元（このカラムが他テーブルを参照している）
        String sql2 = "SELECT TABLE_NAME, CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=? AND REFERENCED_TABLE_NAME IS NOT NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql2)) {
            ps.setString(1, dbName);
            ps.setString(2, tableName);
            ps.setString(3, columnName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        return result;
    }


    // 型名・サイズ・符号を正規化（例: BIGINT→bigint, INT→int, varchar(100)→varchar(100), int unsigned→int unsigned, tinyint(1)→boolean）
    private static String normalizeType(String type) {
        if (type == null) return "";
        String t = type.trim().toLowerCase();

        // 特殊な型の処理
        if (t.startsWith("text")) return "text";
        if (t.startsWith("datetime")) return "datetime";
        if (t.startsWith("timestamp")) return "timestamp";

        // 一般的な型の正規表現（大文字小文字両対応）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-zA-Z]+)(\\([0-9]+\\))?( unsigned)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
        if (m.find()) {
            String base = m.group(1).toLowerCase();
            String size = m.group(2) != null ? m.group(2) : "";
            String unsigned = m.group(3) != null ? m.group(3).trim().toLowerCase() : "";

            // BOOLEAN型の正規化
            if ("tinyint".equals(base) && "(1)".equals(size)) return "boolean";
            if ("bool".equals(base) || "boolean".equals(base)) return "boolean";

            return base + size + (unsigned.isEmpty() ? "" : " " + unsigned);
        }
        return t;
    }

    // 型名部分だけ抽出（例: "VARCHAR(100) NOT NULL"→"varchar(100)", "BOOLEAN DEFAULT FALSE"→"boolean"）
    private static String extractType(String def) {
        if (def == null) return "";
        String defLower = def.toLowerCase();

        // 特殊な型の処理
        if (defLower.startsWith("text")) return "text";
        if (defLower.startsWith("datetime")) return "datetime";
        if (defLower.startsWith("timestamp")) return "timestamp";

        // 一般的な型の正規表現（大文字小文字両対応）
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-zA-Z]+)(\\([0-9]+\\))?( unsigned)?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(def);
        if (m.find()) {
            String base = m.group(1).toLowerCase();
            String size = m.group(2) != null ? m.group(2) : "";
            String unsigned = m.group(3) != null ? m.group(3).trim().toLowerCase() : "";

            // BOOLEAN型の正規化
            if ("tinyint".equals(base) && "(1)".equals(size)) return "boolean";
            if ("bool".equals(base) || "boolean".equals(base)) return "boolean";

            return base + size + (unsigned.isEmpty() ? "" : " " + unsigned);
        }

        // フォールバック：最初の単語を取得
        String[] parts = def.trim().split("\\s+");
        return parts[0].toLowerCase();
    }

    // BOOLEAN型判定
    private static boolean isBooleanType(String type) {
        return "boolean".equals(type) || "tinyint(1)".equals(type);
    }

    // デフォルト値抽出（例: "DEFAULT 'default'"→"default", "DEFAULT FALSE"→"false"）
    private static String extractDefaultValue(String def) {
        if (def == null) return null;
        String lower = def.toLowerCase();
        int idx = lower.indexOf("default");
        if (idx < 0) return null;

        String after = def.substring(idx + 7).trim();
        if (after.isEmpty()) return null;

        // クォート付きの値
        if (after.startsWith("'")) {
            int end = after.indexOf("'", 1);
            if (end > 0) return after.substring(1, end).toLowerCase();
        }

        // クォートなしの値（最初の単語を取得）
        String[] parts = after.split("\\s+");
        String value = parts[0].replace("'", "").toLowerCase();

        // 特殊値の正規化
        if ("false".equals(value) || "0".equals(value)) return "false";
        if ("true".equals(value) || "1".equals(value)) return "true";
        if ("null".equals(value)) return null;

        return value;
    }

    /**
     * テーブルのカラム型の違いを検出し、型や大きさが異なる場合はALTER TABLEで型を修正する
     * @param conn データベース接続
     * @param tableName テーブル名
     * @param idealColumnDefs 理想カラム定義（カラム名→定義）
     * @param targetColumns 対象カラム名セット（matchedカラムのみ）
     * @param log ログ出力関数
     */
    private static void alterColumnTypeIfNeeded(Connection conn, String tableName, java.util.Map<String, String> idealColumnDefs, java.util.Set<String> targetColumns, BiConsumer<String, String> log) throws SQLException {
        String sql = "SHOW COLUMNS FROM " + tableName;
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                String colName = rs.getString("Field");
                if (!targetColumns.contains(colName) || !idealColumnDefs.containsKey(colName)) continue;
                String colType = rs.getString("Type");
                String colNull = rs.getString("Null");
                String colKey = rs.getString("Key");
                String colDefault = rs.getString("Default");
                String colExtra = rs.getString("Extra");
                String idealDef = idealColumnDefs.get(colName);
                if (isColumnDefinitionMatch(colType, colNull, colKey, colDefault, colExtra, idealDef)) {
                    continue;
                }
                // --- 外部キー依存関係を自動検出し一時解除 ---
                java.util.List<String[]> fkList = getRelatedForeignKeys(conn, tableName, colName);
                for (String[] fk : fkList) {
                    String fkTable = fk[0];
                    String fkName = fk[1];
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("ALTER TABLE " + fkTable + " DROP FOREIGN KEY " + fkName);
                        log.accept(fkTable + "." + fkName + " 外部キー制約を一時的に削除", "INFO");
                    } catch (SQLException e) {
                        log.accept(fkTable + "." + fkName + " 外部キー制約の削除時に例外: " + e.getMessage(), "DEBUG");
                    }
                }
                // --- カラム型・制約をALTER TABLEで修正 ---
                String idealDefForAlter = idealDef.replaceAll("(?i)\\s*PRIMARY KEY", "")
                                                 .replaceAll("(?i)\\s*UNIQUE", "")
                                                 .trim();

                // AUTO_INCREMENTが失われている場合の特別処理
                if (idealDef.toLowerCase().contains("auto_increment") &&
                    (colExtra == null || !colExtra.toLowerCase().contains("auto_increment"))) {

                    // 現在のテーブルの最大ID値を取得
                    String maxIdSql = String.format("SELECT IFNULL(MAX(%s), 0) FROM %s", colName, tableName);
                    long maxId = 0;
                    try (Statement stmt = conn.createStatement()) {
                        ResultSet maxIdRs = stmt.executeQuery(maxIdSql);
                        if (maxIdRs.next()) {
                            maxId = maxIdRs.getLong(1);
                        }
                    }

                    // AUTO_INCREMENTを含むALTER TABLE文を実行
                    String alterSql = String.format("ALTER TABLE %s MODIFY %s %s", tableName, colName, idealDefForAlter);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(alterSql);

                        // AUTO_INCREMENTの開始値を設定（既存の最大値+1から開始）
                        if (maxId > 0) {
                            String autoIncrementSql = String.format("ALTER TABLE %s AUTO_INCREMENT = %d", tableName, maxId + 1);
                            stmt.execute(autoIncrementSql);
                            log.accept(tableName + "テーブルの" + colName + "カラムにAUTO_INCREMENT制約を復元し、開始値を" + (maxId + 1) + "に設定しました", "INFO");
                        } else {
                            log.accept(tableName + "テーブルの" + colName + "カラムにAUTO_INCREMENT制約を復元しました", "INFO");
                        }
                    }
                } else {
                    // 通常のカラム型・制約変更
                    String alterSql = String.format("ALTER TABLE %s MODIFY %s %s", tableName, colName, idealDefForAlter);
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute(alterSql);
                        log.accept(tableName + "テーブルの" + colName + "カラム型・制約を" + colType + "→" + idealDefForAlter + "に変更しました", "INFO");
                    }
                }
                // --- 外部キー制約を再作成（DROPしたもののみ） ---
                for (String[] fk : fkList) {
                    String fkTable = fk[0];
                    String fkName = fk[1];
                    try (Statement stmt = conn.createStatement()) {
                        // 外部キー定義をINFORMATION_SCHEMAから取得し再作成
                        String fkDefSql = "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME=?";
                        try (PreparedStatement ps = conn.prepareStatement(fkDefSql)) {
                            ps.setString(1, conn.getCatalog());
                            ps.setString(2, fkTable);
                            ps.setString(3, fkName);
                            ResultSet fkRs = ps.executeQuery();
                            if (fkRs.next()) {
                                String fkCol = fkRs.getString("COLUMN_NAME");
                                String refTable = fkRs.getString("REFERENCED_TABLE_NAME");
                                String refCol = fkRs.getString("REFERENCED_COLUMN_NAME");
                                String addFkSql = String.format("ALTER TABLE %s ADD CONSTRAINT %s FOREIGN KEY (%s) REFERENCES %s(%s)", fkTable, fkName, fkCol, refTable, refCol);
                                stmt.execute(addFkSql);
                                log.accept(fkTable + "." + fkName + " 外部キー制約を再作成", "INFO");
                            }
                        }
                    } catch (SQLException e) {
                        log.accept(fkTable + "." + fkName + " 外部キー制約の再作成時に例外: " + e.getMessage(), "CRITICAL");
                        throw e;
                    }
                }
            }
        }
    }

    /**
     * SHOW COLUMNSの情報と理想定義を「型＋制約���ット」として正規化し、完全一致判定する
     */
    private static boolean isColumnDefinitionMatch(String colType, String colNull, String colKey, String colDefault, String colExtra, String idealDef) {
        // 型名・サイズ・符号
        String normType = normalizeType(colType);
        String normIdealType = normalizeType(extractType(idealDef));

        // 型の比較（BOOLEANとtinyint(1)は同一視）
        if (!normType.equals(normIdealType)) {
            if (!(isBooleanType(normType) && isBooleanType(normIdealType))) {
                return false;
            }
        }

        // 制約セットを正規化して比較
        String[] actualSet = normalizeConstraints(colNull, colKey, colDefault, colExtra);
        String[] idealSet = normalizeIdealConstraints(idealDef);

        // 制約セットの完全一致判定
        for (String c : idealSet) {
            boolean found = false;
            for (String a : actualSet) {
                if (c.equals(a)) { found = true; break; }
            }
            if (!found) return false;
        }
        for (String a : actualSet) {
            boolean found = false;
            for (String c : idealSet) {
                if (a.equals(c)) { found = true; break; }
            }
            if (!found) return false;
        }

        return true;
    }

    // SHOW COLUMNSの制約情報を正規化（NOT NULL, AUTO_INCREMENT, PRIMARY KEY, UNIQUE, DEFAULT）
    private static String[] normalizeConstraints(String colNull, String colKey, String colDefault, String colExtra) {
        java.util.List<String> set = new java.util.ArrayList<>();
        if ("NO".equals(colNull)) set.add("not null");

        // AUTO_INCREMENTの検出を強化（大文字小文字、スペースの違いを吸収）
        if (colExtra != null) {
            String extraLower = colExtra.toLowerCase().trim();
            if (extraLower.contains("auto_increment") || extraLower.contains("auto increment")) {
                set.add("auto_increment");
            }
        }

        if (colKey != null && colKey.equalsIgnoreCase("PRI")) set.add("primary key");
        if (colKey != null && colKey.equalsIgnoreCase("UNI")) set.add("unique");

        // デフォルト値の正規化（BOOLEAN型の特別処理を含む）
        if (colDefault != null) {
            String normalizedDefault = normalizeDefaultValue(colDefault);
            if (normalizedDefault != null) {
                set.add("default:" + normalizedDefault);
            }
        }

        return set.toArray(new String[0]);
    }

    // 理想定義から制約セットを正規化抽出
    private static String[] normalizeIdealConstraints(String idealDef) {
        java.util.List<String> set = new java.util.ArrayList<>();
        String def = idealDef.toLowerCase();
        if (def.contains("not null")) set.add("not null");
        if (def.contains("auto_increment")) set.add("auto_increment");
        if (def.contains("primary key")) set.add("primary key");
        if (def.contains("unique")) set.add("unique");

        // PRIMARY KEYまたはAUTO_INCREMENTが指定されている場合は自動的にNOT NULLが付与される
        if ((def.contains("primary key") || def.contains("auto_increment")) && !set.contains("not null")) {
            set.add("not null");
        }

        // デフォルト値の正規化
        String idealDefault = extractDefaultValue(idealDef);
        if (idealDefault != null) {
            String normalizedDefault = normalizeDefaultValue(idealDefault);
            if (normalizedDefault != null) {
                set.add("default:" + normalizedDefault);
            }
        }

        return set.toArray(new String[0]);
    }

    // デフォルト値を正規化（BOOLEAN型の0/1⇔false/true変換を含む）
    private static String normalizeDefaultValue(String defaultValue) {
        if (defaultValue == null) return null;
        String normalized = defaultValue.trim().toLowerCase();

        // BOOLEAN型の正規化
        if ("0".equals(normalized) || "false".equals(normalized)) return "false";
        if ("1".equals(normalized) || "true".equals(normalized)) return "true";

        // NULL値の処理
        if ("null".equals(normalized)) return null;

        // CURRENT_TIMESTAMPなどの関数の正規化
        if (normalized.startsWith("current_timestamp")) return "current_timestamp";

        // クォートを除去
        normalized = normalized.replace("'", "");

        return normalized;
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
        accessLogDefs.put("source_path", "VARCHAR(500)");
        accessLogDefs.put("collected_at", "TIMESTAMP NULL");
        accessLogDefs.put("agent_registration_id", "VARCHAR(255) NULL");
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
        settingsDefs.put("whitelist_ip", "VARCHAR(370) DEFAULT ''");
        settingsDefs.put("backend_version", "VARCHAR(50) DEFAULT ''");
        settingsDefs.put("frontend_version", "VARCHAR(50) DEFAULT ''");
        settingsDefs.put("access_log_retention_days", "INT DEFAULT 365");
        settingsDefs.put("login_history_retention_days", "INT DEFAULT 365");
        settingsDefs.put("action_execution_log_retention_days", "INT DEFAULT 365");
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
        actionExecutionLogDefs.put("rule_id", "INT NOT NULL");
        actionExecutionLogDefs.put("server_name", "VARCHAR(100) NOT NULL");
        actionExecutionLogDefs.put("trigger_event", "VARCHAR(255) NOT NULL");
        actionExecutionLogDefs.put("execution_status", "VARCHAR(20) NOT NULL DEFAULT 'pending'");
        actionExecutionLogDefs.put("execution_result", "TEXT");
        actionExecutionLogDefs.put("execution_time", "DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
        actionExecutionLogDefs.put("processing_duration_ms", "INT");
        autoSyncTableColumns(conn, "action_execution_log", actionExecutionLogDefs, null, log);

        // エージェント管理用テーブル

        // agent_servers - エージェント登録サーバー管理
        var agentServersDefs = new java.util.LinkedHashMap<String, String>();
        agentServersDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        agentServersDefs.put("registration_id", "VARCHAR(255) UNIQUE NOT NULL");
        agentServersDefs.put("agent_name", "VARCHAR(255) NOT NULL");
        agentServersDefs.put("agent_ip", "VARCHAR(45) NOT NULL");
        agentServersDefs.put("hostname", "VARCHAR(255)");
        agentServersDefs.put("os_name", "VARCHAR(100)");
        agentServersDefs.put("os_version", "VARCHAR(100)");
        agentServersDefs.put("java_version", "VARCHAR(50)");
        agentServersDefs.put("nginx_log_paths", "TEXT");
        agentServersDefs.put("iptables_enabled", "BOOLEAN DEFAULT TRUE");
        agentServersDefs.put("registered_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        agentServersDefs.put("last_heartbeat", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        agentServersDefs.put("status", "VARCHAR(20) DEFAULT 'active'");
        agentServersDefs.put("agent_version", "VARCHAR(50)");
        agentServersDefs.put("tcp_connection_count", "INT DEFAULT 0");
        agentServersDefs.put("last_log_count", "INT DEFAULT 0");
        agentServersDefs.put("total_logs_received", "BIGINT DEFAULT 0");
        agentServersDefs.put("description", "TEXT");
        // 旧カラム名からの移行
        var agentServersMigrate = new java.util.HashMap<String, String>();
        agentServersMigrate.put("server_name", "agent_name");
        agentServersMigrate.put("server_ip", "agent_ip");
        autoSyncTableColumns(conn, "agent_servers", agentServersDefs, agentServersMigrate, log);

        // agent_block_requests - エージェントブロック要求管理
        var agentBlockRequestsDefs = new java.util.LinkedHashMap<String, String>();
        agentBlockRequestsDefs.put("id", "INT AUTO_INCREMENT PRIMARY KEY");
        agentBlockRequestsDefs.put("request_id", "VARCHAR(255) UNIQUE NOT NULL");
        agentBlockRequestsDefs.put("registration_id", "VARCHAR(255) NOT NULL");
        agentBlockRequestsDefs.put("ip_address", "VARCHAR(45) NOT NULL");
        agentBlockRequestsDefs.put("duration", "INT NOT NULL DEFAULT 3600");
        agentBlockRequestsDefs.put("reason", "TEXT");
        agentBlockRequestsDefs.put("chain_name", "VARCHAR(50) DEFAULT 'INPUT'");
        agentBlockRequestsDefs.put("status", "VARCHAR(20) DEFAULT 'pending'");
        agentBlockRequestsDefs.put("created_at", "TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
        agentBlockRequestsDefs.put("processed_at", "TIMESTAMP NULL");
        agentBlockRequestsDefs.put("result_message", "TEXT");
        autoSyncTableColumns(conn, "agent_block_requests", agentBlockRequestsDefs, null, log);

        log.accept("エージェント管理用テーブルのスキーマ同期が完了しました", "INFO");
    }

}
