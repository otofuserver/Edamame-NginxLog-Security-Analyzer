# DbSchema

対象: `src/main/java/com/edamame/security/db/DbSchema.java`

## 概要
- データベースのテーブル定義をコード上で保持し、自動的に既存スキーマと比較して必要なカラム追加・削除・型変更を行うユーティリティクラス。`autoSyncTableColumns` によりスキーマの自動整合を実現する。

## 主な機能
- テーブル存在チェック、新規作成
- 既存テーブルのカラム一覧取得と理想定義との比較
- 不足カラムの追加、不要カラムの削除、型変更の適用
- カラム移行（旧カラム名から新カラム名へデータ移行）

## 挙動
- 指定された `DbSession` を通じて内部的に SQL を実行し、テーブルごとの理想カラム定義（LinkedHashMap）を参照して順次同期処理を行う。
- テーブルがない場合は CREATE TABLE を生成し、カラム定義と制約を追加する。
- 既存テーブルがある場合は差分を取り、必要な ALTER を実行する（追加/削除/型変更/マイグレーション）。

## 細かい指定された仕様
- `activation_tokens` テーブルは `token_hash` を CHAR(64) NOT NULL UNIQUE とし、`user_id` に外部キー制約を付与する設計とする。
- テーブル作成時のデフォルトエンジンは InnoDB、文字セットは utf8mb4、照合順序は utf8mb4_unicode_ci を使用する。
- PRIMARY KEY やテーブル制約は columnDefs 内で特別扱いし、テーブル作成時に末尾へ追加する。
- スキーマ変更はデータ破壊のリスクがあるため、本番環境では事前にバックアップを取得してから実行すること。

## その他
- スキーマ同期は環境やDBのバージョン差により動作が変わる可能性がある。大きなスキーマ変更はマイグレーション手順書を別途用意すること。
- 生成される SQL は MySQL 向けを想定しており、他のDBでは互換性がない可能性がある。

## 主なメソッドと機能（詳細）
- `public static void syncAllTablesSchema(DbSession dbSession)`
  - 全テーブルの理想定義を順次チェック・同期するエントリポイント。内部で `autoSyncTableColumns` を呼び出す。
- `private static void autoSyncTableColumns(DbSession dbSession, String tableName, Map<String,String> columnDefs, Map<String,String> migrateMap)`
  - 指定テーブルの存在確認、CREATE TABLE（必要時）、既存カラムとの差分計算、追加・削除・型変更・移行を行う主要な実装。
- 内部ユーティリティ:
  - `tableExists(Connection conn, String tableName)` - テーブルの存在チェック
  - `getTableColumns(Connection conn, String tableName)` - 現在のカラム一覧取得
  - `compareTableColumns(Set<String> existingColumns, Set<String> idealColumns)` - 差分判定（add/delete/matched）
  - `addMissingColumns(...)` - 不足カラムの追加処理
  - `dropExtraColumns(...)` - 余剰カラムの削除処理
  - `alterColumnTypeIfNeeded(...)` - 型修正が必要なカラムの ALTER 実行
  - `migrateColumnData(...)` - 旧カラムから新カラムへデータ移行を行う

## 変更履歴
- 2.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(db): DbSchema の仕様書を追加
