# AuthenticationFilter 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/security/AuthenticationFilter.java`

責務:
- 各リクエストに対してセッション/クッキー等の認証情報を検証し、`HttpExchange` に `username` 属性を設定する。
- 認証が必要なパスに対して未認証なら `/login` にリダイレクトする。

実装上の注意点:
- セッション管理は安全なクッキー（Secure, HttpOnly, SameSite）を利用すること。
- セッションハイジャック防止のため、セッション固定化対策を導入すること。
