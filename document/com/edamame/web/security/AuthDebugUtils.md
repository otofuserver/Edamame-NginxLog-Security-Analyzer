# AuthDebugUtils

対象: `src/main/java/com/edamame/web/security/AuthDebugUtils.java`

## 概要
- 認証周りの簡易デバッグ / ヘルスチェックユーティリティ。開発時や本番前確認のため、DB のテーブル/レコード存在確認や BCrypt の動作確認などを行う軽量ツールを提供する。

## 主な機能
- `users` テーブルや `admin` ユーザの存在確認（`verifyAuthenticationSystem`）
- BCrypt エンコーダの基本動作確認（`performHealthCheck`）

## 挙動
- `verifyAuthenticationSystem` は DB 接続を受け取り、`SHOW TABLES LIKE 'users'` や `SELECT COUNT(*) FROM users WHERE username = 'admin'` 等で基礎的な存在チェックを行い、結果を `AppLogger` に出力する。
- `performHealthCheck` は `BCryptPasswordEncoder` を利用してハッシュ化/照合の簡易チェックを行い結果をログに出力する。
- これはデバッグユーティリティであり、本番環境での自動実行や外部からのアクセスは制限すること。

## 細かい指定された仕様
- データベース接続は `Connection` を直接受け取るため、呼び出し元で接続制御（権限、トランザクション）を行うこと。
- 結果は `AppLogger` に情報/警告で出力されるのみで、外部 API を介した通知等は含まれない。

## メソッド一覧と機能
- `public static void verifyAuthenticationSystem(Connection connection)` - DB の基本チェックを行う。
- `public static void performHealthCheck(Connection connection)` - BCrypt の基本動作確認を行う。

## その他
- 本ユーティリティは本番環境では限定的に使用すること。脆弱性や情報漏洩を避けるため、アクセス制御を徹底する。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成

## コミットメッセージ例
- docs(web): AuthDebugUtils の仕様書を追加

