# Edamame NginxLog Security Analyzer データベース仕様書（新フォーマット）

## バージョン情報
- **db_schema_spec.md version**: v2.8.3  # action_toolsカラム(tool_type, is_enabled, config_json)追加・順序修正
- **最終更新**: 2025-08-13
- ※DB関連の仕様変更時は必ず本ファイルを更新し、バージョン情報も修正すること
- ※変更履歴は CHANGELOG.md に記録

---

## 1. 仕様管理方針
- すべてのDBスキーマ管理・移行は`DbSchema.syncAllTablesSchema()`による自動同期・自動移行に一本化
- サーバー情報の登録・更新・最終ログ受信時刻の管理は`DbRegistry`で行う
- 旧仕様・旧メソッドは今後一切利用しないこと
- 仕様変更時は本ファイル・CHANGELOG.md・実装（DbSchema.java, DbRegistry.java）を必ず同時更新

---

## 2. テーブル定義一覧

### access_log
- NGINXアクセスログの記録
- エージェント連携・TCPバッチ処理対応

| カラム名 | 型 | 説明 |
|---|---|---|
| id | BIGINT | 主キー、自動採番 |
| server_name | VARCHAR(100) | ログ送信元サーバー名 |
| method | VARCHAR(10) | HTTPメソッド |
| full_url | TEXT | アクセスURL（クエリ含む） |
| status_code | INT | HTTPステータスコード |
| ip_address | VARCHAR(45) | クライアントIP |
| access_time | DATETIME | アクセス日時（ログから抽出）|
| blocked_by_modsec | BOOLEAN | ModSecurityブロック有無 |
| created_at | DATETIME | レコード作成日時 |
| source_path | VARCHAR(500) | ログファイルのソースパス |
| collected_at | TIMESTAMP | ログ収集日時（エージェント用）|
| agent_registration_id | VARCHAR(255) | エージェント登録ID |

### url_registry
- URLごとの安全性・攻撃判定・ホワイトリスト管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| server_name | VARCHAR(100) | URL発見元サーバー名 |
| method | VARCHAR(10) | HTTPメソッド |
| full_url | TEXT | アクセスURL |
| created_at | DATETIME | 登録日時 |
| updated_at | DATETIME | 最終更新日時 |
| is_whitelisted | BOOLEAN | ホワイトリスト対象か（安全性保持） |
| attack_type | VARCHAR(50) | 識別された攻撃タイプ |
| user_final_threat | BOOLEAN | ユーザーの脅威最終判断 |
| user_threat_note | TEXT | 脅威判断に対するメモ欄 |

#### is_whitelisted仕様
- 一度trueになったURLはfalseに戻せない
- 新規登録・安全性向上時のみtrue化
- settings.whitelist_mode/whitelist_ipと連携

### modsec_alerts
- ModSecurityアラート記録

| カラム名 | 型 | 説明 |
|---|---|---|
| id | BIGINT | 主キー、自動採番 |
| access_log_id | BIGINT | access_log.idへの外部キー |
| rule_id | VARCHAR(20) | ModSecurityルールID |
| severity | VARCHAR(20) | 深刻度 |
| message | TEXT | アラートメッセージ |
| data_value | TEXT | 詳細情報 |
| created_at | DATETIME | レコード作成日時 |
| detected_at | DATETIME | 検知日時 |
| server_name | VARCHAR(100) | アラート発生元サーバー名 |

### servers
- サーバー管理

| カラム名 | 型 | 制約・照合順序 | 説明 |
|---|---|---|---|
| id | INT | PRIMARY KEY, AUTO_INCREMENT | 主キー、自動採番 |
| server_name | VARCHAR(100) | NOT NULL, UNIQUE, COLLATE utf8mb4_unicode_ci | サーバー名（ユニーク） |
| server_description | TEXT | COLLATE utf8mb4_unicode_ci | サーバーの説明 |
| log_path | VARCHAR(500) | DEFAULT '', COLLATE utf8mb4_unicode_ci | ログファイルパス |
| is_active | BOOLEAN | | 有効フラグ |
| created_at | DATETIME | | 作成日時 |
| updated_at | DATETIME | | 最終更新日時 |
| last_log_received | DATETIME | | 最終ログ受信日時 |

### settings
- システム設定・保存日数・バージョン管理

| カラム名                                | 型 | 説明 |
|-------------------------------------|---|---|
| id                                  | INT | 固定値: 1（単一レコード管理） |
| whitelist_mode                      | BOOLEAN | ホワイトリスト有効フラグ |
| whitelist_ip                        | VARCHAR(370) | ホワイトリストIPアドレス（カンマ区切りで複数指定可） |
| backend_version                     | VARCHAR(50) | バックエンドバージョン情報 |
| frontend_version                    | VARCHAR(50) | フロントエンドバージョン情報 |
| access_log_retention_days           | INT | アクセスログ保存日数 |
| login_history_retention_days        | INT | ログイン履歴保存日数 |
| action_execution_log_retention_days | INT | アクション実行履歴保存日数 |

### users
- フロントエンド認証用ユーザー管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| username | VARCHAR(50) | ユーザー名（ユニーク） |
| email | VARCHAR(255) | メールアドレス |
| password_hash | VARCHAR(255) | パスワード（BCryptハッシュ化） |
| role_id | INT | ロールID（rolesテーブルへの外部キー） |
| created_at | DATETIME | 作成日時 |
| updated_at | DATETIME | 最終更新日時 |
| is_active | BOOLEAN | 有効フラグ |

### roles
- ロール管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| role_name | VARCHAR(50) | ロール名（ユニーク） |
| description | TEXT | ロールの説明 |
| created_at | DATETIME | 作成日時 |
| updated_at | DATETIME | 最終更新日時 |

### login_history
- ログイン履歴

| カラム名 | 型 | 説明 |
|---|---|---|
| id | BIGINT | 主キー、自動採番 |
| user_id | INT | users.idへの外部キー |
| login_time | DATETIME | ログイン日時 |
| ip_address | VARCHAR(45) | ログイン元IP |
| user_agent | TEXT | ユーザーエージェント |
| success | BOOLEAN | 成功/失敗フラグ |

### sessions
- セッション管理

| カラム名 | 型 | 説明 |
|---|---|---|
| session_id | VARCHAR(64) | 主キー、セッションID |
| username | VARCHAR(255) | ユーザー名 |
| expires_at | DATETIME | 有効期限 |
| created_at | TIMESTAMP | セッション作成日時 |

### action_execution_log
- アクション実行履歴

| カラム名 | 型 | 説明 |
|---|---|---|
| id | BIGINT | 主キー、自動採番 |
| rule_id | INT | 実行したアクションルールID |
| server_name | VARCHAR(100) | 対象サーバー名 |
| trigger_event | VARCHAR(255) | トリガーイベント |
| execution_status | VARCHAR(20) | 実行ステータス |
| execution_result | TEXT | 実行結果メッセージ |
| execution_time | DATETIME | 実行日時 |
| processing_duration_ms | INT | 処理所要時間（ミリ秒） |

### action_rules
- アクション自動実行ルール管理

| カラム名            | 型             | 説明 |
|---------------------|----------------|------|
| id                  | INT            | 主キー、自動採番 |
| rule_name           | VARCHAR(100)   | ルール名（ユニーク） |
| target_server       | VARCHAR(100)   | 対象サーバー名（"*"で全サーバー） |
| condition_type      | VARCHAR(50)    | 条件タイプ（例: status_code, attack_type等） |
| condition_params    | TEXT           | 条件パラメータ（JSON等で複数指定可） |
| action_tool_id      | INT            | 実行アクションツールID（action_tools.idへの外部キー） |
| action_params       | TEXT           | アクションパラメータ（JSON等） |
| is_enabled          | BOOLEAN        | 有効フラグ |
| priority            | INT            | 優先度（数値が小さいほど高優先） |
| last_executed       | DATETIME       | 最終実行日時 |
| execution_count     | INT            | 実行回数 |
| created_at          | DATETIME       | 作成日時 |
| updated_at          | DATETIME       | 最終更新日時 |

### action_tools
- アクション実行ツール定義

| カラム名     | 型           | 説明 |
|--------------|--------------|------|
| id           | INT          | 主キー、自動採番 |
| tool_name    | VARCHAR(100) | ツール名（ユニーク） |
| tool_type    | VARCHAR(50)  | ツール種別（例: shell, http, script等） |
| is_enabled   | BOOLEAN      | 有効フラグ |
| config_json  | TEXT         | ツール設定（JSON形式） |
| description  | TEXT         | ツール説明 |
| created_at   | DATETIME     | 作成日時 |
| updated_at   | DATETIME     | 最終更新日時 |

### agent_servers
- エージェント登録サーバー管理
- 
- **自動削除仕様**: `access_log_retention_days`で指定された日数より古い`last_heartbeat`かつ`status != 'active'`のレコードは自動削除バッチで削除される

| カラム名 | 型 | 制約・照合順序 | 説明 |
|---|---|---|---|
| id | INT | PRIMARY KEY, AUTO_INCREMENT | 主キー、自動採番 |
| registration_id | VARCHAR(255) | UNIQUE NOT NULL | エージェント登録ID |
| agent_name | VARCHAR(255) | NOT NULL | エージェント名 |
| agent_ip | VARCHAR(45) | NOT NULL | エージェントIPアドレス |
| hostname | VARCHAR(255) | | ホスト名 |
| os_name | VARCHAR(100) | | OS名 |
| os_version | VARCHAR(100) | | OSバージョン |
| java_version | VARCHAR(50) | | Javaバージョン |
| nginx_log_paths | TEXT | | NGINXログパス（JSON配列） |
| iptables_enabled | BOOLEAN | DEFAULT TRUE | iptables有効フラグ |
| registered_at | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 登録日時 |
| last_heartbeat | TIMESTAMP | DEFAULT CURRENT_TIMESTAMP | 最終ハートビート受信日時 |
| status | VARCHAR(20) | DEFAULT 'active' | エージェント状態 |
| agent_version | VARCHAR(50) | | エージェントバージョン |
| tcp_connection_count | INT | DEFAULT 0 | TCP接続回数 |
| last_log_count | INT | DEFAULT 0 | 最終ログ送信件数 |
| total_logs_received | BIGINT | DEFAULT 0 | 総ログ受信件数 |
| description | TEXT | | エージェント説明・備考 |

### agent_block_requests
- エージェントブロック要求管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| request_id | VARCHAR(255) | ブロック要求ID（ユニーク） |
| registration_id | VARCHAR(255) | 対象エージェント登録ID |
| ip_address | VARCHAR(45) | ブロック対象IPアドレス |
| duration | INT | ブロック継続時間（秒） |
| reason | TEXT | ブロック理由 |
| chain_name | VARCHAR(50) | iptablesチェーン名 |
| status | VARCHAR(20) | 処理状態 |
| created_at | TIMESTAMP | 要求作成日時 |
| processed_at | TIMESTAMP | 処理完了日時 |
| result_message | TEXT | 処理結果メッセージ |

---

## 3. カラム型自動同期・変更ロジック仕様

- `DbSchema.syncAllTablesSchema()`は、各テーブルの理想カラム定義と現状を比較し、
  - 不足カラムの追加
  - 不要カラムの削除
  - 必要なデータ移行
  - **型や制約の違いがある場合はALTER TABLEで型・制約を自動修正**
- **外部キー制約が存在し型変更が必要な場合は、一時的に外部キー制約を削除→型修正→再作成する**
- **外部キー再作成は、両カラムの型が理想型で揃っている場合のみ実施し、揃っていない場合は次回同期時に再試行する**
- これにより、既存DBの型不一致や符号違いも完全自動で解消される
- 変更不要なカラムにはALTER TABLEを発行しない（型・制約・サイズが完全一致している場合はスキップ）

---

## 4. 仕様変更時のルール
- DB関連の仕様（テーブル・カラム・初期データ・制約等）を変更した場合は**必ず**本ファイルを最新状態に更新すること
- 仕様変更時は、ファイル冒頭のバージョン情報も**必ず**更新すること
- 変更内容は**必ず**CHANGELOG.mdにも記載すること
- 旧DB仕様はspecification.txt等から削除し、本ファイルに集約すること
- 本ルールはプロジェクト全体の品質維持のため厳守すること

---

# DbSchema.java 仕様書

## 役割
- データベーススキーマ管理クラス。
- DBの初期テーブル構造作成・カラム存在確認・追加・削除・移行・自動同期を提供。
- 主要全テーブルのスキーマ自動整合（カラム追加・削除・移行）を一括実行。

## 主なメソッド
- `public static void syncAllTablesSchema(DbService dbService)`
  - 主要全テーブルのスキーマ自動整合（カラム追加・削除・移行）を一括実行する。
- `public static void autoSyncTableColumns(DbService dbService, String tableName, Map<String, String> idealColumnDefs, Map<String, String> migrateMap)`
  - テーブルごとに理想カラム定義・移行マップを受け取り、カラム追加・削除・データ移行を自動実行する。
- `private static void addMissingColumns(DbService dbService, String tableName, Set<String> columnsToAdd, Map<String, String> columnDefs)`
  - 不足カラムを追加する。
- `private static void dropExtraColumns(DbService dbService, String tableName, Set<String> columnsToDelete)`
  - 不要カラムを削除する。
- `private static void migrateColumnData(DbService dbService, String toTable, String fromTable, String fromCol, String toCol)`
  - 旧カラムから新カラムへデータ移行を行う。
- `private static void alterColumnTypeIfNeeded(DbService dbService, String tableName, Map<String, String> idealColumnDefs, Set<String> targetColumns)`
  - カラム型の違いを検出し、型や制約が異なる場合はALTER TABLEで型・制約を自動修正する。AUTO_INCREMENT復元機能も含む。
- `private static boolean tableExists(Connection conn, String tableName)`
  - テーブル存在チェックを行う。
- `private static void addColumn(Connection conn, String tableName, String columnName, String columnDef)`
  - テーブルにカラムを追加する。
- `private static Set<String> getTableColumns(Connection conn, String tableName)`
  - テーブルのカラム一覧を取得する。
- `private static Map<String, Set<String>> compareTableColumns(Set<String> existingColumns, Set<String> idealColumns)`
  - カラム差分（追加・削除・一致）を判定する。
- `private static List<String[]> getRelatedForeignKeys(Connection conn, String tableName, String columnName)`
  - 指定カラムに関係する外部キー制約をすべて取得（参照元・参照先両方）する。
- `private static boolean isColumnDefinitionMatch(String colType, String colNull, String colKey, String colDefault, String colExtra, String idealDef)`
  - SHOW COLUMNSの情報と理想定義を「型＋制約セット」として正規化し、完全一致判定を行う。
- `private static String normalizeType(String type)`
  - 型名・サイズ・符号（unsigned）を正規化し、表記ゆれを吸収する。BOOLEAN型の特別処理を含む。
- `private static String extractType(String def)`
  - 理想定義から型名＋サイズ＋符号部分のみを抽出する。
- `private static String[] normalizeConstraints(String colNull, String colKey, String colDefault, String colExtra)`
  - SHOW COLUMNSの制約情報（NOT NULL, AUTO_INCREMENT, PRIMARY KEY, UNIQUE, DEFAULT）を正規化し配列化する。
- `private static String[] normalizeIdealConstraints(String idealDef)`
  - 理想定義から制約セット（NOT NULL, AUTO_INCREMENT, PRIMARY KEY, UNIQUE, DEFAULT）を正規化し配列化する。PRIMARY KEY/AUTO_INCREMENTの自動NOT NULL付与を含む。
- `private static String extractDefaultValue(String def)`
  - カラム定義からデフォルト値を抽出する。
- `private static String normalizeDefaultValue(String defaultValue)`
  - デフォルト値を正規化（BOOLEAN型の0/1⇔false/true変換、CURRENT_TIMESTAMP関数正規化を含む）する。
- `private static boolean isBooleanType(String type)`
  - BOOLEAN型かどうかを判定する（boolean, tinyint(1)を対象）。

## ロジック
- テーブルが存在しない場合は理想カラム定義でCREATE TABLE。
- 既存テーブルはSHOW COLUMNSで現状取得→理想定義と比較→不足カラム追加・不要カラム削除。
- 型・制約の不一致がある場合はALTER TABLEで自動修正（外部キー制約の一時削除・再作成を含む）。
- AUTO_INCREMENT制約が失われている場合は既存データの最大ID値を取得して安全に復元。
- PRIMARY KEY/AUTO_INCREMENTには自動的にNOT NULL制約が付与されることを考慮した比較ロジック。
- 旧カラム→新カラムへのデータ移行も自動実行（migrateMap指定時）。
- 主要全テーブル（access_log, url_registry, modsec_alerts, servers, settings, users, roles, login_history, sessions, action_execution_log, agent_servers, agent_block_requests）に対応。
- ログ出力はINFO/DEBUGで明確に記録。

## 注意事項
- テーブル・カラム追加/削除/リネーム時は必ず理想カラム定義・移行マップを更新すること。
- 仕様変更時は本仕様書・実装・db_schema_spec.md・CHANGELOG.mdを同時更新。
- 例外時はcatchでエラーログを出力し、必要に応じてリトライ。

---

### 参考：実装例
- syncAllTablesSchemaはpublic staticメソッドとして実装。
- テーブルごとにLinkedHashMapで理想カラム定義を記述。
- カラム移行はHashMapで旧→新カラム名を指定。
- ログ出力はINFO/DEBUGで明確に記録。
