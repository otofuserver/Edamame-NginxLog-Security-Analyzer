# LogoutController 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/controller/LogoutController.java`

責務:
- POST `/logout` を受け付け、セッション情報を削除してログアウト処理を行う。
- ログアウト後は `/login?logout=success` にリダイレクトする（フロントエンドも同様の挙動を期待）。

セキュリティ:
- CSRF トークンの検証を行うこと。ログアウトは副作用を伴う操作として保護する。

エラー処理:
- ログアウト失敗時は 500 を返すか、フロントエンドでエラーメッセージを表示する。

備考:
- フロントエンドの `confirmLogout()` が `fetch('/logout', { method: 'POST', credentials: 'same-origin' })` を使っているため、CORS 設定や SameSite 属性に注意する。
