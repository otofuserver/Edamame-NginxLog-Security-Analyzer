# MailActionHandler
対象: `src/main/java/com/edamame/security/action/MailActionHandler.java`

## 概要
MailActionHandler はメール送信に関するロジックを ActionEngine から分離して実装したハンドラです。SMTP 設定の読み込み、接続チェック、テンプレート変換、単一アドレス送信、ロール指定送信（ロール階層を含む）といった処理を担当します。

## 主な機能
- SMTP 設定ファイルの探索と読み込み
- SMTP 接続確認（キャッシュ付き）
- 直接メールアドレス指定送信
- 指定ロールに紐づくユーザーへ送信（`users_roles` を優先、`users.role_id` をフォールバック）
- 指定ロールおよび上位ロールを含めて送信（`roles.inherited_roles` を辿る探索）
- 変数置換テンプレート（`{var}` 書式）

## 挙動
1. コンストラクタで `initializeSmtpConfig()` を呼び SMTP 設定をロードする。
2. メール送信時はまず SMTP サーバーの到達性を `isSmtpServerAvailable` で確認する。
3. `sendToAddress` は SMTP 設定に基づいて JavaMail (javax.mail) を使って同期送信する。
4. `sendToRole` はまず中間テーブル `users_roles` 経由でメールを取得し、空なら `users.role_id` 経由でフォールバックする。
5. `sendToRoleIncludingHigherRoles` は起点ロールの id を取得し、`roles.inherited_roles` JSON をたどって親ロールを BFS で列挙、得られた roleIds から送信先ユーザーを取得する。JSON_CONTAINS の形式（数値配列 / 文字列配列）両方に対応するための SQL を使用する。

## 細かい指定された仕様
- 設定ファイル探索パス（優先順）:
  - `container/config/smtp_config.json`
  - `/app/config/smtp_config.json`
  - `config/smtp_config.json`
  - `smtp_config.json`
- smtp_config.json の想定構造（最小）:
  {
    "smtp": {
      "host": "...",
      "port": 25,
      "auth_required": false,
      "username": "...",
      "password": "...",
      "security": "NONE|STARTTLS|SSL",
      "timeout": 30,
      "connection_timeout": 15,
      "enable_debug": false
    },
    "defaults": {
      "from_email": "noreply@example.com",
      "from_name": "Edamame Security Analyzer"
    }
  }
- SMTP 接続チェックキャッシュ仕様:
  - キャッシュキー: `host:port`
  - TTL: 300000 ms (5 分)
  - チェックはソケットを用いてタイムアウト 15 秒で接続試行
- ロール探索 SQL:
  - 親検索: JSON_CONTAINS に対して数値 JSON と文字列 JSON の両方を試す SQL を使用
    ```sql
    SELECT id FROM roles WHERE JSON_CONTAINS(inherited_roles, CAST(? AS JSON)) OR JSON_CONTAINS(inherited_roles, CONCAT('"', ?, '"'))
    ```
  - users 抽出（優先）:
    ```sql
    SELECT DISTINCT u.email FROM users u JOIN users_roles ur ON u.id = ur.user_id WHERE ur.role_id IN (...) AND u.is_active = 1
    ```
  - users 抽出（フォールバック）:
    ```sql
    SELECT u.email FROM users u WHERE u.role_id IN (...) AND u.is_active = 1
    ```
- ログ出力:
  - 重要イベントは INFO、詳細デバッグは DEBUG、例外は ERROR で出力する
  - 送信対象 roleIds と recipients は INFO ログで記録される（起動時にログ設定しやすくするため）
- 例外処理:
  - DB エラーは適切にログ記録され、呼び出し側にエラーメッセージを返す
  - メール送信失敗時は例外メッセージとスタックトレースをログ（DEBUG）に残す

## 存在するメソッドと機能
- constructor: `public MailActionHandler()`
  - 役割: SMTP 設定を初期化する
- `public String executeMailAction(String configJson, Map<String,Object> eventData)`
  - 機能: ActionEngine から渡される JSON 設定に従いメールを送信する（テンプレート置換含む）
  - 引数: configJson (アクション設定の JSON 文字列), eventData (変数置換用の Map)
  - 戻り値: 実行結果の説明文字列（成功/失敗）
- `public String sendToAddress(String fromEmail, String fromName, String toEmail, String subject, String body)`
  - 機能: 単一アドレスへ同期送信
  - 引数: 送信元・送信先・件名・本文
  - 戻り値: 送信結果メッセージ
  - ログ: 送信前に SMTP host/port/auth_required/to を INFO ログに出力。失敗時はエラーログとスタックトレースを DEBUG ログに残す
- `public String sendToRole(String role, String fromEmail, String fromName, String subject, String body)`
  - 機能: 指定ロール名に紐づくユーザーに送信（上位ロールは含めない）
  - 戻り値: 送信成功数の要約
- `public String sendToRoleIncludingHigherRoles(String role, String fromEmail, String fromName, String subject, String body)`
  - 機能: 指定ロールおよび上位ロールに紐づくユーザーを列挙して送信する。`roles.inherited_roles` を BFS で探索して roleIds を収集する。
  - フォールバック: JSON 検索で例外が発生した場合は targetRoleId のみで処理。users_roles で取得できない場合は users.role_id を利用して再検索する
- `private JSONObject createDefaultSmtpConfig()`
  - 機能: デフォルト SMTP 設定オブジェクトを返す（ファイルが無ければこれを利用）
- `private void initializeSmtpConfig()`
  - 機能: 設定ファイルを読み込み、失敗時はデフォルトを使用
- `private JSONObject getSmtpConfig()`
  - 機能: ロード済み SMTP 設定を返す（未ロードなら初期化を行う）
- `private JSONObject loadSmtpConfigFromFile() throws Exception`
  - 機能: 上述のパスから smtp_config.json を探索して読み込む。見つからなければ例外を投げる
- `private boolean isSmtpServerAvailable(String host, int port)`
  - 機能: ソケット接続で到達性をチェックし、結果をキャッシュする
- `private String replaceVariables(String template, Map<String,Object> eventData)`
  - 機能: テンプレートのプレースホルダ `{key}` を eventData の値で置換する
- 内部クラス `private static class SmtpCheckResult`:
  - フィールド: boolean available, long timestamp
  - 機能: SMTP 接続チェックのキャッシュ用レコード

## その他
- 依存: JavaMail (javax.mail), org.json, com.edamame.security.db.DbService, com.edamame.security.tools.AppLogger
- DB スキーマ想定: `users(id,email,is_active,role_id?)`, `users_roles(user_id,role_id)`, `roles(id,role_name,inherited_roles)`
- セキュリティ: SMTP 認証情報は暗号化済み設定ファイルで管理されることを推奨。テスト送信は本番で無効化されるべき。

## 変更履歴
- v1.0.0 - 2025-11-01: 初期実装（切り出し版）
- v1.1.0 - 2026-01-01: JSON_CONTAINS のフォーマット対応強化、role 階層探索とログ出力強化

## コミットメッセージ例
- docs(action): MailActionHandler の仕様書を追加
- feat(action): JSON_CONTAINS の探索を数値/文字列両対応に変更

