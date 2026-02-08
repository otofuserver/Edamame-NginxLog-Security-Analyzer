# AuthenticationService

対象: `src/main/java/com/edamame/web/security/AuthenticationService.java`

## 概要
- ユーザー認証とセッション管理を提供するサービスクラス。パスワード照合（BCrypt）、セッション作成・検証・削除、ログイン履歴の記録、期限切れセッションの定期クリーンアップ等を担う。
- 2026-02 追加: 初回ログイン時のパスワード変更強制フラグ（`must_change_password`）と変更日時を扱い、認証結果に反映する。

## 主な機能
- 認証（`authenticate`）: ユーザー名/パスワード照合、成功時にセッション作成とログ記録、初回パスワード変更要否（`mustChangePassword`）を返す
- セッション管理（`createSession`, `validateSession`, `isValidSession`, `deleteSession`）
- ログイン履歴記録（`insertLoginHistory`）
- 期限切れセッションの定期クリーンアップ（scheduler による定期実行）
- 現在パスワード検証（`verifyPassword`）

## 挙動
- `authenticate` は DB（`users` テーブル）から `password_hash` と `must_change_password` / `password_changed_at` を取得し、`BCryptPasswordEncoder` で照合する。成功時は `AuthResult`（`sessionId`, `mustChangePassword`）を返却。
- セッションは `sessions` テーブルに `session_id, username, expires_at` として保存され、`rememberMe` フラグで有効期限を延長できる（デフォルト 24 時間、rememberMe 時 30 日）。
- `validateSession` は `sessions` と `users` を JOIN して `must_change_password` も読み、`SessionInfo` に保持する。期限切れ/無効な場合は null。
- `cleanupExpiredSessions` は DB から期限切れレコードを削除し、定期実行により運用を維持する。

## 細かい指定された仕様
- セッション有効期限はデフォルトで `SESSION_TIMEOUT_HOURS = 24`。`rememberMe` が true の場合は 30 日に延長する。
- セッション作成時は UUID を sessionId として生成する。
- DB 操作は `DbService.getConnection()` を通じて行い、SQLException はログに記録し null/false を返すなどで安全に扱う。
- `insertLoginHistory` は `login_history` テーブルへ成功/失敗と IP/User-Agent を記録する。
- `verifyPassword` は現在パスワード照合専用で、強制パスワード変更フロー（`/password/change`）で使用される。

## 主なメソッド
- `public AuthenticationService()`
- `public AuthResult authenticate(String username, String password, boolean rememberMe, String ipAddress, String userAgent)`
- `public String createSession(String username, boolean rememberMe)`
- `public SessionInfo validateSession(String sessionId)`
- `public boolean isValidSession(String sessionId)`
- `public void deleteSession(String sessionId)`
- `public void cleanupExpiredSessions()`
- `public void insertLoginHistory(String username, boolean success, String ipAddress, String userAgent)`
- `public boolean verifyPassword(String username, String plainPassword)`
- `public void logout(String sessionId)`
- `public void shutdown()`
- `public static class SessionInfo { String getUsername(); String getSessionId(); LocalDateTime getExpiresAt(); boolean isExpired(); boolean isMustChangePassword(); }`

## その他
- セッション ID の取り扱いは Cookie の Secure/HttpOnly 設定や SameSite 属性と合わせて設計すること。
- 大量のログイン試行や不審なリクエストに対してはレート制限や IP ブロックなど追加の保護層を設けることを推奨する。

## 変更履歴
- 1.0.0 - 2025-12-30: ドキュメント作成
- 1.1.0 - 2026-02-08: 初回パスワード変更必須フラグと AuthResult 返却を追記

## コミットメッセージ例
- docs(web): AuthenticationService の仕様書を統一フォーマットへ変換
