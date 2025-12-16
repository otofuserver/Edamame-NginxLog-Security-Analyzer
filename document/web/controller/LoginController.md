# LoginController 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/controller/LoginController.java`

責務:
- ログイン画面の表示とログイン POST 処理。
- 認証成功時にはセッションを設定し、リダイレクトでダッシュボードに遷移させる。

入力:
- POST `/login` フォーム: `username`, `password`（平文送信ではなく HTTPS と CSRF 保護を前提）

出力:
- 成功: 302 リダイレクト -> `/main`
- 失敗: ログイン画面にエラーメッセージ表示（HTTP 200）または 401

セキュリティ:
- パスワードはサーバ側で BCrypt（`BCryptPasswordEncoder`）によりハッシュと照合する。
- CSRF 対策、ログイン試行回数制限（レートリミット）を実装することが望ましい。

拡張:
- OAuth2 や SSO 連携の追加を見据えた抽象化を推奨。
