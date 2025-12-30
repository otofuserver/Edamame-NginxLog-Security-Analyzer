# DbSchema

対象: `src/main/java/com/edamame/security/db/DbSchema.java`

## 概要
- データベーススキーマの自動整合ツール。テーブル作成、カラム追加・削除、旧カラムから新カラムへの移行、カラム型の調整などを行う。

## 主な機能
- アプリ起動時に主要テーブルの定義を同期（`syncAllTablesSchema`）
- 指定テーブルについて理想カラム定義と実テーブルを比較し、不足カラムの追加/不要カラムの削除/移行を実行（`autoSyncTableColumns`）
- テーブル作成（CREATE TABLE）や ALTER 操作の実行ユーティリティ

## 挙動
- 起動時に `DbService.syncAllTablesSchema()` 経由で呼ばれる。各テーブルの理想定義（カラム名→DDL）を連結したマップで保持し、既存カラムと比較して差分を適用する。
- 旧カラム名→新カラム名のマッピングを受け取ることで既存データの移行処理を行う（`migrateColumnData` 呼び出し）
- カラム追加は ALTER TABLE ADD COLUMN、削除は ALTER TABLE DROP COLUMN を使って適用する

## 細かい指定された仕様
- テーブル作成時のデフォルト文字セットは utf8mb4_unicode_ci、ストレージエンジンは InnoDB を利用。
- PRIMARY KEY やテーブル制約は columnDefs 内で特別扱いし、テーブル作成時に末尾へ追加する。
- 互換性保持のため既存カラム名のマイグレーション処理を備える（migrateMap を指定）。

## 主なメソッド
- `public static void syncAllTablesSchema(DbSession dbSession)`
- `private static void autoSyncTableColumns(DbSession dbSession, String tableName, Map<String,String> columnDefs, Map<String,String> migrateMap)`
- 内部ユーティリティ: `tableExists`, `getTableColumns`, `compareTableColumns`, `addMissingColumns`, `dropExtraColumns`, `alterColumnTypeIfNeeded`, `migrateColumnData` 等

## 変更履歴
- 2.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(db): DbSchema の仕様書を追加

