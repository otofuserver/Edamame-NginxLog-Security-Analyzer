# DbInitialData.java 仕様書

## 役割
- DB初期データ投入ユーティリティ。
- settings/roles/users/action_tools/action_rules等の初期レコードを投入する。
- テーブルが空の場合のみ初期データを投入し、既存データがあればスキップする。

## DbService専用実装（v2.0.0～）
- **Connection引数方式は廃止**し、DbServiceインスタンスを受け取る方式に統一。
- Connection管理・例外処理・ログ出力はDbServiceが担当。
- 直接ConnectionやBiConsumer loggerを渡す方式はサポートしない。

## 主なメソッド
- `public static void initializeDefaultData(DbService dbService, String appVersion)`
  - settingsテーブルの初期レコード挿入（バージョン・保存日数等）
  - rolesテーブルの初期ロール（administrator, monitor）挿入
  - usersテーブルの初期管理者ユーザー（admin/admin123, BCryptハッシュ）挿入
  - action_toolsテーブルの初期アクションツール（mail_alert, iptables_block等）挿入
  - action_rulesテーブルの初期ルール（攻撃検知時メール通知）挿入
  - 各テーブルが空の場合のみ実行、既存データがあればスキップ
  - ログ出力はdbService.log(message, level)でINFO/ERRORレベルを指定

## プライベートメソッド
- `private static void initializeSettingsTable(DbService dbService, String appVersion)`
- `private static void initializeRolesTable(DbService dbService)`
- `private static void initializeUsersTable(DbService dbService)`
- `private static void initializeActionToolsTable(DbService dbService)`
- `private static void initializeActionRulesTable(DbService dbService)`
  - 各テーブルの初期データを挿入。テーブルが空の場合のみ実行。

## ロジック
- settings/roles/users/action_tools/action_rulesの各テーブルについて、COUNT(*)で空かどうか判定
- 空の場合のみ、プリペアドステートメントで初期データをINSERT
- パスワードはBCryptでハッシュ化（adminユーザー）
- 依存関係（例：roles→users、action_tools→action_rules）は順序を考慮して投入
- 例外発生時はRuntimeExceptionでラップし、dbService.logでERROR出力
- **トランザクション管理はDbServiceで実施**

## DbServiceでの使用方法
```java
// DbServiceから自動的に呼び出される（Connection管理不要）
try (DbService db = new DbService(url, props, logger)) {
    DbInitialData.initializeDefaultData(db, appVersion);
}
```

## 初期データ内容

### settingsテーブル
- id: 1（固定）
- whitelist_mode: false
- whitelist_ip: ""
- backend_version: appVersion
- frontend_version: ""
- 各保存日数: 365日

### rolesテーブル
- administrator: 管理者（全機能アクセス可能）
- monitor: 監視メンバー（ログ閲覧・分析機能のみ）

### usersテーブル
- username: admin
- password: admin123（BCryptハッシュ化）
- email: admin@example.com
- role: administrator

### action_toolsテーブル
- mail_alert: メール通知ツール（有効）
- iptables_block: iptables連携ツール（無効）
- cloudflare_block: Cloudflare連携ツール（無効）
- webhook_notify: Webhook通知ツール（無効）
- daily_report_mail: 日次レポートメール（無効）
- weekly_report_mail: 週次レポートメール（無効）
- monthly_report_mail: 月次レポートメール（無効）

### action_rulesテーブル
- 攻撃検知時メール通知ルール（有効、優先度10）

## 注意事項
- **DbService経由での呼び出しのみサポート**: Connection引数を意識する必要がない。
- 初期ユーザーのパスワードは「admin123」（初回ログイン後に変更を推奨）
- テーブル追加時は本メソッドの対応も必ず追加すること
- ログ出力はINFO/ERRORレベルで明確に記録

## エラーハンドリング
- 例外はcatchでERRORログ出力後、RuntimeExceptionでラップして再スロー。
- DbServiceが例外処理・ログ出力を一元管理。

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応、Connection管理の自動化
- **v2.0.0**: Connection引数方式を完全廃止、DbService専用に統一

---

### 参考：実装例
- initializeDefaultDataはpublic staticメソッドとして実装
- 例外時はcatchでERRORログを出力
- 依存テーブルの順序に注意（roles→users、action_tools→action_rules）
- 各テーブルの初期データはJava配列で定義し、バッチ挿入
- DbServiceパターンにより、Connection引数を意識せずに利用可能。
