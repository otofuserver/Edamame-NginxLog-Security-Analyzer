# DbInitialData.java 仕様書

## 役割
- DB初期データ投入ユーティリティ。
- settings/roles/users/action_tools/action_rules等の初期レコードを投入する。
- テーブルが空の場合のみ初期データを投入し、既存データがあればスキップする。

## 主なメソッド
- `public static void initializeDefaultData(Connection conn, String appVersion, BiConsumer<String, String> log)`
  - settingsテーブルの初期レコード挿入（バージョン・保存日数等）
  - rolesテーブルの初期ロール（administrator, monitor）挿入
  - usersテーブルの初期管理者ユーザー（admin/admin123, BCryptハッシュ）挿入
  - action_toolsテーブルの初期アクションツール（mail_alert, iptables_block等）挿入
  - action_rulesテーブルの初期ルール（攻撃検知時メール通知）挿入
  - 各テーブルが空の場合のみ実行、既存データがあればスキップ
  - ログ出力はBiConsumer<String, String> logでINFO/ERRORレベルを指定

## ロジック
- settings/roles/users/action_tools/action_rulesの各テーブルについて、COUNT(*)で空かどうか判定
- 空の場合のみ、プリペアドステートメントで初期データをINSERT
- パスワードはBCryptでハッシュ化（adminユーザー）
- 依存関係（例：roles→users、action_tools→action_rules）は順序を考慮して投入
- 例外発生時はSQLExceptionをスローし、呼び出し元でロールバック

## 注意事項
- 初期ユーザーのパスワードは「admin123」（初回ログイン後に変更を推奨）
- テーブル追加時は本メソッドの対応も必ず追加すること
- ログ出力はINFO/ERRORレベルで明確に記録

---

### 参考：実装例
- initializeDefaultDataはpublic staticメソッドとして実装
- 例外時はSQLExceptionをスロー
- 依存テーブルの順序に注意（roles→users、action_tools→action_rules）
- 各テーブルの初期データはJava配列で定義し、バッチ挿入