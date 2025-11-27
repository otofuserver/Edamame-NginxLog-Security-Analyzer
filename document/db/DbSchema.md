# Edamame NginxLog Security Analyzer データベース仕様書（新フォーマット）

## バージョン情報
- **db_schema_spec.md version**: v2.9.1
- **最終更新**: 2025-11-28
- ※本ファイルは現在の実装（`DbSchema.syncAllTablesSchema` の元の定義）に合わせて更新されています。
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
- システム設定・保存日数管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 固定値: 1（単一レコード管理） |
| whitelist_mode | BOOLEAN | ホワイトリスト有効フラグ |
| whitelist_ip | VARCHAR(370) | ホワイトリストIPアドレス（カンマ区切りで複数指定可） |
| log_retention_days | INT | ログ保存日数（全ログテーブル統一） |

### users
- フロントエンド認証用ユーザー管理

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| username | VARCHAR(50) | ユーザー名（ユニーク） |
| email | VARCHAR(255) | メールアドレス |
| password_hash | VARCHAR(255) | パスワード（BCryptハッシュ化） |
| created_at | DATETIME | 作成日時 |
| updated_at | DATETIME | 最終更新日時 |
| is_active | BOOLEAN | 有効フラグ |

### users_roles
- ユーザー・ロール多対多管理（中間テーブル）

| カラム名 | 型 | 説明 |
|---|---|---|
| user_id | INT | users.idへの外部キー |
| role_id | INT | roles.idへの外部キー |
| PRIMARY KEY | (user_id, role_id) | 複合主キー（重複禁止） |

### roles
- ユーザーロール管理
- inherited_roles: 継承する下位ロールID配列（JSON形式、複数可）。addDefaultRoleHierarchyでサーバー名付き下位ロールIDを自動追加。

| カラム名 | 型 | 説明 |
|---|---|---|
| id | INT | 主キー、自動採番 |
| role_name | VARCHAR(50) | ロール名（ユニーク） |
| description | TEXT | ロール説明 |
| inherited_roles | TEXT | 継承する下位ロールID配列（JSON形式、複数可） |
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
- **自動削除仕様**: `log_retention_days`で指定された日数より古い`last_heartbeat`かつ`status != 'active'`のレコードは自動削除バッチで削除される

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

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- データベーススキーマ管理・自動同期クラス
- テーブル構造の自動作成・カラム追加・削除・移行処理
- 既存DBからの円滑なバージョンアップ対応
- エージェント管理システム対応の拡張スキーマ

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド

### `syncAllTablesSchema(DbSession dbSession)`
- **機能**: 全テーブルのスキーマ自動整合を一括実行
- **対象テーブル**: 15テーブル（基本ログ管理＋エージェント管理＋認証管理）
- **自動処理**: テーブル作成・カラム追加・削除・データ移行・型修正
- **例外処理**: SQLException → RuntimeExceptionでラップ

### `autoSyncTableColumns(DbSession, String, Map<String,String>, Map<String,String>)`
- **機能**: 個別テーブルのカラム構成自動同期
- **引数**:
  - `tableName`:  対象テーブル名
  - `columnDefs`:  理想的なカラム定義（カラム名→型定義）
  - `migrateMap`: 旧カラム名→新カラム名のマッピング（null許可）
- **処理フロー**: 
  1. テーブル存在確認→新規作成
  2. 既存カラム一覧取得→差分判定
  3. 不足カラム追加→データ移行→不要カラム削除→型修正



## 自動スキーマ同期機能

### カラム追加・削除処理
- **自動判定**: 理想定義と既存構造の差分を自動検出
- **安全な削除**: 不要カラムの自動削除（データ消失リスク）
- **型修正**: カラム型・サイズの自動調整

### データ移行処理
- **同一テーブル内移行**: 旧カラム→新カラムのデータコピー
- **条件付き移行**: NULL/空文字の場合のみ上書き
- **別テーブル間移行**: IDベースの関連付け移行（拡張可能）

### カラム移行マッピング例
    // modsec_alerts テーブル
    // modsecMigrate.put("msg", "message");           // msg → message
    // modsecMigrate.put("data", "data_value");       // data → data_value

    // servers テーブル
    // serversMigrate.put("description", "server_description");     // description → server_description
    // serversMigrate.put("last_activity_at", "last_log_received"); // last_activity_at → last_log_received

    // settings テーブル（統一化）
    // settingsMigrate.put("access_log_retention_days", "log_retention_days");
    // settingsMigrate.put("login_history_retention_days", "log_retention_days");
    // settingsMigrate.put("action_execution_log_retention_days", "log_retention_days");

    // agent_servers テーブル
    // agentServersMigrate.put("server_name", "agent_name");        // server_name → agent_name
    // agentServersMigrate.put("server_ip", "agent_ip");           // server_ip → agent_ip

## エラーハンドリング

### 例外処理階層
1. **個別メソッド**: SQLException → RuntimeExceptionでラップ
2. **syncAllTablesSchema**: 全体エラーをキャッチしてERRORログ出力
3. **DbSession**: Connection管理・トランザクション管理を自動化

### ログ出力レベル
- **INFO**: テーブル作成・カラム追加・削除・データ移行完了時
- **DEBUG**: 既存テーブル確認時
- **ERROR**: SQL実行エラー・致命的例外

## パフォーマンス考慮事項
- **インクリメンタル同期**: 必要な変更のみ実行
- **バッチ処理**: 複数カラムの一括処理
- **型修正最適化**: matched カラムのみ対象

## セキュリティ対策
- **文字エンコーディング**: utf8mb4_unicode_ci統一
- **SQLインジェクション対策**: 動的SQL構築時の適切なエスケープ
- **権限制御**: 管理者権限でのスキーマ変更のみ許可

## 運用・設定

### 初回セットアップ
標準的な使用パターン（例）:
    try {
        DbSchema.syncAllTablesSchema(dbSession);
        AppLogger.info("データベーススキーマ同期完了");
    } catch (SQLException e) {
        AppLogger.error("スキーマ同期エラー: " + e.getMessage());
    }

### バージョンアップ時
- **自動移行**: 旧バージョンからの円滑なアップグレード
- **後方互換性**: 既存データの保持・適切な移行
- **ロールバック**: 必要に応じた手動復旧対応

## 注意事項
- **データ消失リスク**: 不要カラム削除時の確認
- **型変更影響**: 既存データとの互換性確認
- **外部キー制約**: 関連テーブル間の整合性維持
- **大量データ**: 移行処理時のパフォーマンス影響

## デバッグ・トラブルシューティング

### よくある問題
1. **外部キー制約エラー**: 関連テーブルの作成順序
2. **文字エンコーディング問題**: utf8mb4統一の確認
3. **型変更エラー**: 既存データとの互換性確認

### ログ確認ポイント
- スキーマ同期完了メッセージの確認
- カラム追加・削除・移行の件数確認
- エラー時の詳細SQL例外メッセージ

## バージョン履歴
- **v1.0.0**: 初期実装（基本テーブルのみ）
- **v1.5.0**: 自動カラム同期機能追加
- **v2.0.0**: Connection引数方式廃止、DbService専用
- **v2.1.0**: **DbSession対応、エージェント管理テーブル追加、カラム移行機能強化**
- **v2.9.1**: rolesテーブル継承ロール仕様追加・addDefaultRoleHierarchy仕様反映
- **v2.9.2**: スキーマ互換性対応（action_tools / action_rules）

#### roles（ロール管理）
SQL例（roles 作成）:
    CREATE TABLE roles (
        id INT AUTO_INCREMENT PRIMARY KEY,
        role_name VARCHAR(50) NOT NULL UNIQUE,
        description TEXT,
        inherited_roles TEXT,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

---

## 2025-11-27 - v2.9.2: スキーマ互換性対応（action_tools / action_rules）

- 目的: 起動時の初期データ投入で "Unknown column" / "Field ... doesn't have a default value" といったエラーが発生したため、スキーマと初期データの乖離を解消し、後方互換性を確保しました。

- 変更点（実装側: `DbSchema.java` / `DbInitialData.java`）:
  - `action_tools` テーブルに以下カラムを追加（互換のため）:
    - `command_template` (TEXT)
    - `tool_type` にデフォルト値 `DEFAULT 'shell'` を設定（既存の初期データが `tool_type` を提供しないケースに対応）
  - `action_rules` テーブルに以下カラムを追加（互換のため）:
    - `condition_expression` (TEXT)
    - `is_active` (BOOLEAN DEFAULT TRUE)
    - `description` (TEXT)
  - `DbInitialData.java` にて初期データ挿入ロジックを互換化（起動時にテーブルのカラム存在を動的判定し、存在するカラムのみを使用してINSERTを行う）

- 運用上の注意:
  - 本変更は既存DBに不足しているカラムを自動追加することを想定しています。必ずステージング環境で起動確認を行い、本番適用前にDBのフルバックアップを取得してください。
  - もしプロジェクトの方針として「初期データが正」でスキーマを制約する場合は、今回追加したスキーマ変更をリバートする手順を別途実行してください（Backwards-compatibility の維持方針を明確にすること）。
