# AuthenticationService

対象: `src/main/java/com/edamame/web/security/AuthenticationService.java`

## 概要
- ユーザー認証とセッション管理を提供するサービスクラス。パスワード照合（BCrypt）、セッション作成・検証・削除、ログイン履歴の記録、期限切れセッションの定期クリーンアップ等を担う。

## 主な機能
- 認証（`authenticate`）: ユーザー名/パスワード照合、成功時にセッション作成とログ記録
- セッション管理（`createSession`, `validateSession`, `isValidSession`, `deleteSession`）
- ログイン履歴記録（`insertLoginHistory`）
- 期限切れセッションの定期クリーンアップ（scheduler による定期実行）

## 挙動
- `authenticate` は DB（`users` テーブル）から password_hash を取得し、`BCryptPasswordEncoder` で照合する。成功時は `createSession` を呼び sessionId を返す。
- セッションは `sessions` テーブルに `session_id, username, expires_at` として保存され、`rememberMe` フラグで有効期限を延長できる（デフォルト 24 時間、rememberMe 時 30 日）。
- `validateSession` は `sessions` を参照して有効期限を確認し、`SessionInfo` を返す。
- `cleanupExpiredSessions` は DB から期限切れレコードを削除し、定期実行により運用を維持する。

## 細かい指定された仕様
- セッション有効期限はデフォルトで `SESSION_TIMEOUT_HOURS = 24`。`rememberMe` が true の場合は 30 日に延長する。
- セッション作成時は UUID を sessionId として生成する。
- DB 操作は `DbService.getConnection()` を通じて行い、SQLException はログに記録し null/false を返すなどで安全に扱う。
- `insertLoginHistory` は `login_history` テーブルへ成功/失敗と IP/User-Agent を記録する。

## 主なメソッド
- `public AuthenticationService()`
- `public String authenticate(String username, String password, boolean rememberMe, String ipAddress, String userAgent)`
- `public String createSession(String username, boolean rememberMe)`
- `public SessionInfo validateSession(String sessionId)`
- `public boolean isValidSession(String sessionId)`
- `public void deleteSession(String sessionId)`
- `public void cleanupExpiredSessions()`
- `public void insertLoginHistory(String username, boolean success, String ipAddress, String userAgent)`
- `public void logout(String sessionId)`
- `public void shutdown()`
- `public static class SessionInfo { String getUsername(); String getSessionId(); LocalDateTime getExpiresAt(); boolean isExpired(); }`

## その他
- セッション ID の取り扱いは Cookie の Secure/HttpOnly 設定や SameSite 属性と合わせて設計すること。
- 大量のログイン試行や不審なリクエストに対してはレート制限や IP ブロックなど追加の保護層を設けることを推奨する。

## 変更履歴
- 1.0.0 - 2025-12-30: ドキュメント作成

## コミットメッセージ例
- docs(web): AuthenticationService の仕様書を統一フォーマットへ変換
