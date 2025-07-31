# Edamame NginxLog Security Analyzer データベース仕様書

## db_schema_spec.md バージョン情報
- **db_schema_spec.md version**: v2.2.2
- ※このファイルを更新した場合は、必ず上記バージョン情報も更新すること
- ※DB関連の仕様変更時は必ず本ファイルを更新してください
- ※変更履歴は CHANGELOG.md に記録されます

---

## 重要: 旧DB管理システムの廃止につ��て
- 2025年7月以降、**旧DB管理システム（手動ALTERや個別メソッド呼び出し等）は完全廃止**
- すべてのDBスキーマ管理・移行は`DbSchema.syncAllTablesSchema()`による**自動同期・自動移行**に一本化
- サーバー情報の登録・更新・最終ログ受信時刻の管理は`DbRegistry.registerOrUpdateServer`および`DbRegistry.updateServerLastLogReceived`で行う（旧DbSchemaの個別メソッド呼び出しは廃止済み）
- 旧仕様・旧メソッドは今後一切利用しないこと
- 仕様変更時は本ファイル・CHANGELOG.md・実装（DbSchema.java, DbRegistry.java）を必ず同時更新
- **2025年7月: migrateColumnDataの旧シグネチャ（同一テーブル専用）を廃止し、移行元テーブル指定型に統一**

---

## データベース構造（MySQL 8.x, 2025年7月版）

### テーブル自動管理・移行仕様
- すべてのテーブル・カラムは`DbSchema.java`の自動スキーマ同期ロジックで管理される
- テーブルが存在しない場合は自動作成
- カラム追加・削除・リネーム・データ移行も自動で行われる
- 旧カラム（description, last_activity_at等）は初回起動時に自動で統合・リネーム・削除
- 文字列カラムは`utf8mb4_unicode_ci`で統一
- 仕様変更時はこのファイルと`CHANGELOG.md`を必ず更新
- **2025年7月: カラムデータ移行ロジックはmigrateColumnData(移行元テーブル指定型)に一本化。呼び出し元も全て新シグネチャに統一済み。**

---

### `access_log`
| カラム名         | 型              | 説明                       |
|------------------|------------------|----------------------------|
| id               | BIGINT           | 主キー、自動採番         |
| server_name      | VARCHAR(100)     | ログ送信元サーバー名      |
| method           | VARCHAR(10)      | HTTPメソッド              |
| full_url         | TEXT             | アクセスURL（クエリ含む） |
| status_code      | INT              | HTTPステータスコード      |
| ip_address       | VARCHAR(45)      | クライアントIP            |
| access_time      | DATETIME         | アクセス日時（ログから抽出）|
| blocked_by_modsec| BOOLEAN          | ModSecurityブロック有無    |
| created_at       | DATETIME         | レコード作成日時          |

### `url_registry`
| カラム名         | 型              | 説明                                 |
|------------------|------------------|--------------------------------------|
| id               | INT              | 主キー、自動採番                   |
| server_name      | VARCHAR(100)     | URL発見元サーバー名                 |
| method           | VARCHAR(10)      | HTTPメソッド                        |
| full_url         | TEXT             | アクセスURL                         |
| created_at       | DATETIME         | 登録日時（自動設定）               |
| updated_at       | DATETIME         | 最終更新日時（自動更新）           |
| is_whitelisted   | BOOLEAN          | ホワイトリスト対象か               |
| attack_type      | VARCHAR(50)      | 識別された攻撃タイプ               |
| user_final_threat| BOOLEAN          | ユーザーの脅威最終判断             |
| user_threat_note | TEXT             | 脅威判断に対するメモ欄             |

### `modsec_alerts`
| カラム名         | 型              | 説明                                  |
|------------------|------------------|---------------------------------------|
| id               | BIGINT           | 主キー、自動採番                    |
| access_log_id    | BIGINT           | `access_log.id` への外部キー         |
| rule_id          | VARCHAR(20)      | ModSecurityルールID                  |
| severity         | VARCHAR(20)      | 深刻度                               |
| message          | TEXT             | アラートメッセージ                   |
| data_value       | TEXT             | 詳細情報                             |
| created_at       | DATETIME         | レコード作成日時                     |
| detected_at      | DATETIME         | 検知日時                             |
| server_name      | VARCHAR(100)     | アラート発生元サーバー名             |

### `servers`（サーバー管理）
| カラム名           | 型              | 制約・照合順序                | 説明                           |
|--------------------|------------------|-------------------------------|--------------------------------|
| id                 | INT              | PRIMARY KEY, AUTO_INCREMENT   | 主キー、自動採番               |
| server_name        | VARCHAR(100)     | NOT NULL, UNIQUE, COLLATE utf8mb4_unicode_ci | サーバー名（ユニーク）         |
| server_description | TEXT             | COLLATE utf8mb4_unicode_ci    | サーバーの説明                 |
| log_path           | VARCHAR(500)     | DEFAULT '', COLLATE utf8mb4_unicode_ci | ログファイルパス               |
| is_active          | BOOLEAN          |                               | 有効フラグ                     |
| created_at         | DATETIME         |                               | 作成日時                       |
| updated_at         | DATETIME         |                               | 最終更新日時                   |
| last_log_received  | DATETIME         |                               | 最終ログ受信日時               |

#### 備考
- 旧カラム（description, last_activity_at等）は自動でserver_description, last_log_receivedへ統合・リネーム・削除される
- すべての文字列カラムはutf8mb4_unicode_ciで統一

### `settings`
| カラム名                          | 型              | 説明                                               |
|-----------------------------------|------------------|----------------------------------------------------|
| id                                | INT              | 固定値: 1（単一レコード管理）                      |
| whitelist_mode                    | BOOLEAN          | ホワイトリスト有効フラグ                           |
| whitelist_ip                      | VARCHAR(45)      | 登録用のIPアドレス                                 |
| backend_version                   | VARCHAR(50)      | バックエンドバージョン情報                         |
| frontend_version                  | VARCHAR(50)      | フロントエンドバージョン情報                       |
| access_log_retention_days         | INT              | アクセスログ＋modsec_alertsの保存日数（単位:日）    |
| login_history_retention_days      | INT              | ログイン履歴の保存日数（単位:日）                   |
| action_execution_log_retention_days| INT              | アクション実行履歴の保存日数（単位:日）             |

#### 備考
- 各ログテーブルの自動削除日数を管理（NULLまたは0の場合は無制限保存）
- 日次���ッチで該当日数を超えた古いレコードを自動削除

### `users`（フロントエンド認証用）
| カラム名         | 型              | 説明                           |
|------------------|------------------|--------------------------------|
| id               | INT              | 主キー、自動採番               |
| username         | VARCHAR(50)      | ユーザー名（ユニーク）         |
| email            | VARCHAR(255)     | メールアドレス                 |
| password_hash    | VARCHAR(255)     | パスワード（BCryptハッシュ化） |
| role_id          | INT              | ロールID（rolesテーブルへの外部キー） |
| created_at       | DATETIME         | 作成日時                       |
| updated_at       | DATETIME         | 最終更新日時                   |
| is_active        | BOOLEAN          | 有効フラグ                     |

#### 仕様変更（2025-07-24）
- `last_login`カラムは廃止。ログイン履歴は`login_history`テーブルで一元管理。

#### 初期ユーザー自動追加仕様
- usersテーブル新規作成時、初期ユーザー（admin/admin123, admin@example.com, is_active=True）を自動追加
- 初期ユーザーには自動で管理者ロール（administrator）が設定される
- パスワードはbcryptでハッシュ化して保存
- 本番運用時は初期パスワードを必ず変更すること

### `roles`（ロール管理）
| カラム名         | 型              | 説明                           |
|------------------|------------------|--------------------------------|
| id               | INT              | 主キー、自動採番               |
| role_name        | VARCHAR(50)      | ロール名（ユニーク）           |
| description      | TEXT             | ロールの説明                   |
| created_at       | DATETIME         | 作成日時                       |
| updated_at       | DATETIME         | 最終更新日時                   |

#### 初期ロール自動追加仕様
- rolesテーブル新規作成時、以下の初期ロールを自動追加：
  - administrator：管理者（すべての機能にアクセス可能）
  - monitor：監視メンバー（ログ閲覧と基本的な分析機能のみ）

### `login_history`（ログイン履歴）
| カラム名         | 型              | 説明                           |
|------------------|------------------|--------------------------------|
| id               | BIGINT           | 主キー、自動採番               |
| user_id          | INT              | users.id への外部キー           |
| login_time       | DATETIME         | ログイン日時                   |
| ip_address       | VARCHAR(45)      | ログイン元IP                   |
| user_agent       | TEXT             | ユーザーエージェント           |
| success          | BOOLEAN          | 成功/失敗フラグ                |

#### 補足
- すべてのログイン履歴はこのテーブルで管理し、`users`テーブルの`last_login`カラムは廃止。
- 直近のログイン日時が必要な場合は`login_history`から取得すること。

---

### 認証・セッション管理用テーブル


### `sessions`（セッション管理）
| カラム名      | 型             | 説明                       |
|--------------|----------------|----------------------------|
| session_id   | VARCHAR(64)    | 主キー、セッションID       |
| username     | VARCHAR(255)   | ユーザー名                 |
| expires_at   | DATETIME       | 有効期限                   |
| created_at   | TIMESTAMP      | セッション作成日時         |

#### 備考
- セッションIDはUUIDベース
- 有効期限は通常24時間、"ログインしたままにする"時は30日間
- 1時間ごとに期限切れセッションを自動削除

---

### `action_execution_log`（アクション実行履歴）
| カラム名           | 型              | 説明                                 |
|--------------------|------------------|--------------------------------------|
| id                 | BIGINT           | 主キー、自動採番                     |
| action_rule_id     | INT              | 実行したアクションルールID（action_rules.idへの外部キー） |
| action_tool_id     | INT              | 実行したアクションツールID（action_tools.idへの外部キー） |
| target_server      | VARCHAR(100)     | 対象サーバー名                       |
| executed_at        | DATETIME         | 実行日時                             |
| status             | VARCHAR(20)      | 実行ステータス（success, failed等）  |
| result_message     | TEXT             | 実行結果メッセージ                   |
| params_json        | TEXT             | 実行時パラメータ（JSON形式）         |

#### 備考
- すべてのアクション実行履歴はこのテーブルで管理
- statusは`success`/`failed`/`skipped`等を記録
- params_jsonには実行時のパラメータ（IP, URL, attack_type等）をJSONで保存

---

## DB仕様変更時のルール
- DB関連の仕様（テーブル・カラム・初期データ・制約等）を変更した場合は**必ず**本ファイルを最新状態に更新すること
- 仕様変更時は、ファイル冒頭のバージョン情報も**必ず**更新すること
- 変更内容は**必ず**CHANGELOG.mdにも記載すること
- 旧DB仕様はspecification.txt等から削除し、本ファイルに集約すること
- 本ルールはプロジェクト全体の品質維持のため厳守すること
