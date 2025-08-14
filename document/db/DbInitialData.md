# DbInitialData.java 仕様書

## バージョン情報
- **version**: v2.9.0  # usersテーブルrole_id廃止・users_roles中間テーブル対応
- **最終更新**: 2025-08-14

## 概要
- DB初期データ投入ユーティリティ
- settings/roles/users/users_roles/action_tools/action_rules等の初期レコードを投入
- テーブルが空の場合のみ初期データを投入し、既存データがあればスキップ
- アプリケーション初回起動時��マスターリセット時に使用

## 主要メソッド

### `public static void initializeDefaultData(DbSession dbSession)`
- **機能**: 全初期データテーブルの一括初期化
- **引数**: dbSession: データベースセッション
- **実行順序**: 依存関係を考慮した順序で初期化
  1. `settings` (基本設定)
  2. `roles` (ユーザーロール)
  3. `users` (初期管理者、rolesに依存)
  4. `users_roles` (ユーザー・ロール紐付け)
  5. `action_tools` (アクションツール)
  6. `action_rules` (アクションルール、action_toolsに依存)
- **エラーハンドリング**: RuntimeException → SQLExceptionでラップして再スロー

## プライベートメソッド詳細

### `initializeSettingsTable(DbSession dbSession)`
**初期設定データ**:
| id | whitelist_mode | whitelist_ip | log_retention_days |
|----|----------------|--------------|-------------------|
| 1  | false          | （空）       | 365               |

### `initializeRolesTable(DbSession dbSession)`
**初期ロールデータ**:
| role_name | description |
|-----------|------------|
| admin     | 管理者     |
| operator  | オペレーター|
| viewer    | 閲覧者     |

### `initializeUsersTable(DbSession dbSession)`
**初期管理者ユーザー**:
- ユーザー名: `admin`
- メール: `admin@example.com`
- パスワード: `admin123` (BCryptでハッシュ化)
- ステータス: アクティブ (`is_active=TRUE`)

### `users_roles` 初期データ
- adminユーザー（user_id=1）にadminロール（role_id=1）を付与
- 複数ロール付与時はusers_rolesにuser_id, role_idの組み合わせを追加

### `initializeActionToolsTable(DbSession dbSession)`
**初期アクションツール**:
| tool_name    | command_template | description |
|-------------|------------------|-------------|
| iptables    | iptables -I INPUT -s {ip} -j DROP | iptablesによるIP遮断 |
| fail2ban    | fail2ban-client set nginx-limit-req banip {ip} | fail2banによるIP遮断 |
| notification| curl -X POST ... | Slack/Discord通知 |

### `initializeActionRulesTable(DbSession dbSession)`
**初期アクションルール**:
| rule_name              | condition_expression | action_tool_id | is_active | description |
|------------------------|---------------------|---------------|-----------|-------------|
| high_severity_block    | severity >= 3       | 1             | TRUE      | 高レベル攻撃の自動遮断 |
| repeated_attack_block  | attack_count >= 5   | 1             | TRUE      | 繰り返し攻撃の自動遮断 |
| critical_attack_notify | severity >= 4       | 3             | TRUE      | 重要攻撃の通知 |

## 仕様変更履歴
- v2.9.0: usersテーブルrole_id廃止・users_roles中間テーブル追加・初期データ投入ロジック修正

## 注意事項
- users_rolesテーブルでユーザーとロールの多対多管理
- 初期管理者ユーザーはadminロールのみ付与（複数ロールはusers_rolesで追加可能）
- 仕様変更時は本ファイル・DbSchema.md・CHANGELOG.mdを同時更新
