# AuthenticationService

対象: `src/main/java/com/edamame/web/security/AuthenticationService.java`

## 概要
- ユーザー認証・セッション管理・ログイン履歴記録を担うサービス。BCrypt照合による認証、セッション発行/検証/削除、期限切れセッションの定期クリーンアップ、ログイン失敗多発時の自動IPブロックとブロック期間終了に合わせたクリーンアップ予約を行う。

## 主な機能
- 認証: `authenticate` でユーザー名/パスワード照合し、成功時にセッション生成・履歴記録・パスワード変更要否を返却。
- セッション管理: `createSession`/`validateSession`/`logout`/`cleanupExpiredSessions` 等でライフサイクルを管理。
- ログイン履歴: `insertLoginHistory` で成功/失敗・IP・User-Agentを記録。
- 自動IPブロック: 5分間に5回以上の失敗で `block_ip` にAPP_LOGIN用途のACTIVE行を10分間登録し、ブロック中は429で拒否できる状態を提供。
- ブロックIPクリーンアップ: `runBlockIpCleanupBatch` 呼び出しをスケジューラに一件だけ予約し、最短 `end_at` +1分で再スケジュールする。

## 挙動
- `authenticate` は先に `isActiveLoginBlockExists` でブロックを確認し、ブロック中は履歴記録のみ行ってnullを返す。認証成功時は `AuthResult(sessionId, mustChangePassword)` を返す。
- 失敗時は `insertLoginHistory` で記録し、同一IP��直近5分失敗回数が5件以上なら `registerLoginBlock` で block_ip に ACTIVE をINSERTし、直後に最短 `end_at` を再読込してクリーンアップを予約する。
- 起動時コンストラクタで block_ip の最短 `end_at` を1回読み取り、`CLEANUP_OFFSET_MINUTES`(+1分)を足した時刻で一度だけ予約。クリーンアップ後は `runBlockIpCleanupBatch` 内のフックで再スケジュール。
- セッションは `sessions` テーブルに UUID を保存し、有効期限はデフォルト24時間・rememberMe時30日。`validateSession` で `must_change_password` も読み取る。
- `cleanupExpiredSessions` は1時間ごとに期限切れセッションを削除。

## 細かい指定された仕様
- セッション有効期限: `SESSION_TIMEOUT_HOURS = 24`、rememberMe時は30日。
- ログイン失敗の自動ブロック閾値: 5分間に5回（`LOGIN_FAIL_THRESHOLD`/`LOGIN_FAIL_WINDOW_MINUTES`）。ブロック期間は10分（`AUTO_BLOCK_DURATION_MINUTES`）。
- ブロック判定/登録は `block_ip` テーブルを VARBINARY(16) の IP で扱い、`service_type='APP_LOGIN'`、`status='ACTIVE'`、`end_at` を参照。
- クリーンアップ予約は同時に1件のみ保持し、既存予約があればキャンセルの上で最短 end_at+1分を予約。
- DB��作は `DbService.getConnection()` 経由。SQLException 発生時はロギングし安全側（ブロック判定失敗時は非ブロック扱い）で処理。
- ��歴保存は login_history に成功/失敗を残し、ユーザー名・IP・User-Agent を記録。

## 主なメソッド（Java）
- `public AuthenticationService()`
- `public AuthResult authenticate(String username, String password, boolean rememberMe, String ipAddress, String userAgent)`
- `public boolean isLoginBlocked(String ipAddress)`
- `public String createSession(String username, boolean rememberMe)`
- `public SessionInfo validateSession(String sessionId)` / `public boolean isValidSession(String sessionId)`
- `public void deleteSession(String sessionId)` / `public void logout(String sessionId)`
- `public void cleanupExpiredSessions()`
- `public void insertLoginHistory(String username, boolean success, String ipAddress, String userAgent)`
- `public boolean verifyPassword(String username, String plainPassword)`
- `public String getUsernameBySessionId(String sessionId)`
- `private void recordLoginFailure(String username, String ipAddress, String userAgent)`
- `private int countRecentLoginFailures(String ipAddress)`
- `private boolean isActiveLoginBlockExists(String ipAddress)`
- `private void registerLoginBlock(String ipAddress)`
- `private void scheduleBlockIpCleanup(LocalDateTime runAt)` / `private void runBlockIpCleanupAndReschedule()` / `private void scheduleNextBlockIpCleanupFromDatabase()`
- `private byte[] toIpBytes(String ipAddress)`
- `public static class SessionInfo { getUsername(), getSessionId(), getExpiresAt(), isExpired(), isMustChangePassword() }`

## その他
- ブロック中判定は SQLException が起きた場合に偽として扱うため、監視で異常検知する運用を推奨。
- ログインフォーム側では429時に汎用エラーメッセージを表示し、攻撃者へのヒントを与えない。
- セッションIDは Cookie の Secure/HttpOnly/SameSite 設定と併用する前提。

## 変更履歴
- 1.0.0 - 2025-12-30: ドキュメント作成
- 1.1.0 - 2026-02-08: must_change_password フローを追記
- 1.2.0 - 2026-03-16: ログイン失敗による自動IPブロックと block_ip クリーンアップ予約の仕様、429応答判定を追記

## コミットメッセージ例
- docs(web): AuthenticationService の仕様をIPブロック対応に更新
