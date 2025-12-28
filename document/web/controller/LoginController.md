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
- 成功: 302 リダイレクト -> `/main?view=dashboard`
- 失敗: ログイン画面にエラーメッセージ表示（HTTP 200）または 401

セキュリティ:
- パスワードはサーバ側で BCrypt（`BCryptPasswordEncoder`）によりハッシュと照合する。
- CSRF 対策、ログイン試行回数制限（レートリミット）を実装することが望ましい。

拡張:
- OAuth2 や SSO 連携の追加を見据えた抽象化を推奨。

## 2025-12-29 更新: ログイン後リダイレクト先の明示化

- 変更概要:
  - ログイン成功時および既にログイン済みで `/login` にアクセスした場合のリダイレクト先を明示的に `/main?view=dashboard` に変更しました。

- 理由:
  - ログイン直後にクライアント側がどの view を初期化すべきかを一貫させるため。クライアントは URL の `view` パラメータまたは `#main-content` の `data-view` を参照して初期化を行います。

- 影響範囲:
  - ログイン成功後の URL が `/main` から `/main?view=dashboard` に変わります。これにより SPA ライクな初期化が安定します。

- テスト手順:
  1. ログインフォームで認証を行い、リダイレクト先の URL が `/main?view=dashboard` になっていることを確認。  
  2. リダイレクト先で `#main-content` に `data-view` があれば、その値を確認し、クライアント側の初期化が正しく行われることを確認。
