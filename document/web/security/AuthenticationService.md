# AuthenticationService 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/security/AuthenticationService.java`

責務:
- ユーザー認証ロジック（資格情報検証、セッション生成、Remember-me トークン管理など）を提供する。
- パスワード検証は BCrypt を使用する。

API:
- `authenticate(username, password)` -> 成功時にユーザー情報/セッション ID を返す。
- `invalidateSession(sessionId)` -> セッション破棄

セキュリティ:
- パスワードのログ出力は厳禁。
- 複数回失敗によるアカウントロックやレートリミットを考慮する。
