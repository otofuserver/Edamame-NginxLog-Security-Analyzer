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

### email_change_requests テーブル仕様（追加）
- 目的: ユーザーが自分のメールアドレスを変更する際に「メール所有者確認」フローの一時リクエストを保存する。
- 主キー/カラム:
  - `id` BIGINT AUTO_INCREMENT PRIMARY KEY
  - `user_id` INT NOT NULL  -- users(id) を参照
  - `new_email` VARCHAR(255) NOT NULL
  - `code_hash` CHAR(64) NOT NULL  -- 送信した6桁コードのハッシュ（SHA-256 等）を保存
  - `is_used` BOOLEAN DEFAULT FALSE
  - `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP
  - `expires_at` DATETIME NULL  -- 有効期限（例: created_at + 15分）
  - `request_ip` VARCHAR(45) DEFAULT ''
  - `attempts` INT DEFAULT 0  -- 検証試行回数
- 外部キー: `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`
- 挙動（実装上の期待）:
  - 新しいメール変更リクエスト作成時に6桁コードを生成してメール送信し、`code_hash` にハッシュを保存する。生コードは保存しないこと。
  - デフォルトの有効期限は短め（実装では 15 分が推奨）。期限切れは `expires_at` をみて判定する。
  - `verify` 処理は受け取ったコードをハッシュ化して `code_hash` と比較する。成功時は `is_used` を true に更新し、`users.email` をトランザクション内で更新する。
  - `attempts` をインクリメントして閾値（例: 5回）を超えた場合は検証を拒否するかリクエストを無効化するロジックを実装することを推奨する。
- セキュリティ注意点:
  - `code_hash` は SHA-256 等の安全なハッシュで保存し、ソルトや HMAC の利用を検討する（単純なハッシュだけだと辞書攻撃のリスクがある）。
  - メール送信には送信ログ・監査を実装し、不正アクセスの痕跡を追えるようにする。

## url_registry テーブル仕様（最終アクセス情報）
- 目的: URL脅威度一覧の表示最適化のため、最終アクセス情報を url_registry に保持する。
- 追加カラム:
  - `latest_access_time` DATETIME  — 最終アクセス日時
  - `latest_status_code` INT — 最終アクセス時のHTTPステータスコード
  - `latest_blocked_by_modsec` BOOLEAN DEFAULT FALSE — 最終アクセスがModSecurityでブロックされたか
- 備考: `DbSchema.syncAllTablesSchema` により自動で追加・同期される。

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
- 2026-01-05: `email_change_requests` テーブルを追加（メール変更の所有者確認フロー用）
- 2026-01-15: url_registry に最終アクセス情報カラムを追加（latest_access_time, latest_status_code, latest_blocked_by_modsec）

## コミットメッセージ例
- docs(db): DbSchema に email_change_requests の仕様を追加
- docs(db): url_registry に最終アクセス情報カラムを追加
