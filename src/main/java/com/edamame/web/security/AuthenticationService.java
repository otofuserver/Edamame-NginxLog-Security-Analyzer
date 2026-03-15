package com.edamame.web.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledFuture;
import static com.edamame.security.db.DbService.*;
import com.edamame.security.tools.AppLogger;

/**
 * 認証サービスクラス
 * ユーザー認証とセッション管理を担当
 */
public class AuthenticationService {

    private final BCryptPasswordEncoder passwordEncoder;
    private final ScheduledExecutorService scheduler;
    private final ScheduledExecutorService blockIpScheduler;
    private ScheduledFuture<?> blockIpCleanupFuture;

    // セッション有効期限（デフォルト: 24時間）
    private static final long SESSION_TIMEOUT_HOURS = 24;
    private static final int LOGIN_FAIL_THRESHOLD = 5;
    private static final long LOGIN_FAIL_WINDOW_MINUTES = 5;
    private static final long AUTO_BLOCK_DURATION_MINUTES = 10;
    private static final long CLEANUP_OFFSET_MINUTES = 1;

    /**
     * コンストラクタ
     */
    public AuthenticationService() {
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.blockIpScheduler = Executors.newScheduledThreadPool(1);

        // ブロックIPクリーンアップ後に次回予約を自動セットするフックを登録
        registerBlockIpCleanupRescheduler(this::scheduleNextBlockIpCleanupFromDatabase);

        // 期限切れセッションのクリーンアップを1時間ごとに実行
        scheduler.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 1, java.util.concurrent.TimeUnit.HOURS);

        // 起動時に block_ip の最短 end_at を参照し、クリーンアップを一度予約
        scheduleNextBlockIpCleanupFromDatabase();
    }



    /**
     * 認証結果を表すレコード
     * @param sessionId セッションID
     * @param mustChangePassword 初回ログイン等でパスワード変更が必須かどうか
     */
    public record AuthResult(String sessionId, boolean mustChangePassword) { }

    /**
     * APP_LOGIN用途でIPがブロック中か判定
     * @param ipAddress クライアントIP
     * @return ブロック中ならtrue
     */
    public boolean isLoginBlocked(String ipAddress) {
        try {
            return ipAddress != null && !ipAddress.isBlank() && isActiveLoginBlockExists(ipAddress);
        } catch (SQLException e) {
            AppLogger.warn("IPブロック判定失敗: " + e.getMessage());
            return false;
        }
    }

    /**
     * ユーザー認証を実行（IPアドレス・User-Agent対応）
     * @param username ユーザー名
     * @param password パスワード
     * @param rememberMe ログイン状態維持フラグ
     * @param ipAddress クライアントIPアドレス
     * @param userAgent ユーザーエージェント
     * @return 認証結果（失敗時はnull）
     */
    public AuthResult authenticate(String username, String password, boolean rememberMe, String ipAddress, String userAgent) {
        if (username == null || password == null) {
            return null;
        }
        // 先にIPブロックを判定し、ブロック中は即座に拒否
        try {
            if (ipAddress != null && !ipAddress.isBlank() && isActiveLoginBlockExists(ipAddress)) {
                recordLoginFailure(username, ipAddress, userAgent);
                AppLogger.warn("ブロック中IPからのログイン試行を拒否: " + ipAddress);
                return null;
            }
        } catch (SQLException e) {
            AppLogger.error("ブロック判定エラー: " + e.getMessage());
        }
        String sql = "SELECT password_hash, must_change_password, password_changed_at FROM users WHERE username = ? AND is_active = TRUE";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                boolean mustChangeFlag = rs.getBoolean("must_change_password");
                boolean mustChange = mustChangeFlag || rs.getTimestamp("password_changed_at") == null;
                if (passwordEncoder.matches(password, storedHash)) {
                    String sessionId = createSession(username, rememberMe);
                    insertLoginHistory(username, true, ipAddress, userAgent);
                    AppLogger.info("ユーザー認証成功: " + username + " (mustChange=" + mustChange + ")");
                    return new AuthResult(sessionId, mustChange);
                }
            }
            recordLoginFailure(username, ipAddress, userAgent);
            AppLogger.warn("ユーザー認証失敗: " + username);
            return null;
        } catch (SQLException e) {
            AppLogger.error("認証処理失敗: " + e.getMessage());
            return null;
        }
    }

    /**
     * セッションを作成
     * @param username ユーザー名
     * @param rememberMe ログイン状態維持フラグ
     * @return セッションID
     */
    public String createSession(String username, boolean rememberMe) {
        String sessionId = UUID.randomUUID().toString();
        long timeoutHours = rememberMe ? 24 * 30 : SESSION_TIMEOUT_HOURS;
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(timeoutHours);
        String sql = "INSERT INTO sessions (session_id, username, expires_at) VALUES (?, ?, ?)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            stmt.setString(2, username);
            stmt.setTimestamp(3, java.sql.Timestamp.valueOf(expiresAt));
            stmt.executeUpdate();
            AppLogger.debug("セッション作成: " + username + " (rememberMe: " + rememberMe + ")");
        } catch (SQLException e) {
            AppLogger.error("セッション作成失敗: " + e.getMessage());
            return null;
        }
        return sessionId;
    }

    /**
     * セッションを検証（DB参照）
     * @param sessionId セッションID
     * @return SessionInfo（有効な場合）/ null（無効）
     */
    public SessionInfo validateSession(String sessionId) {
        if (sessionId == null) return null;
        String sql = "SELECT s.username, s.expires_at, u.must_change_password, u.password_changed_at FROM sessions s JOIN users u ON s.username = u.username WHERE s.session_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    LocalDateTime expiresAt = rs.getObject("expires_at", LocalDateTime.class);
                    boolean mustChange = rs.getBoolean("must_change_password") || rs.getTimestamp("password_changed_at") == null;
                    if (expiresAt != null && expiresAt.isAfter(LocalDateTime.now())) {
                        String username = rs.getString("username");
                        AppLogger.debug("セッション認証成功: " + username + " (" + sessionId + ")");
                        return new SessionInfo(username, sessionId, expiresAt, mustChange);
                    } else {
                        AppLogger.warn("期限切れセッション: " + sessionId);
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.error("セッション認証エラー: " + e.getMessage());
        }
        return null;
    }

    /**
     * セッションが有効かチェック（DB参照）
     * @param sessionId セッションID
     * @return 有効性
     */
    @SuppressWarnings("unused")
    public boolean isValidSession(String sessionId) {
        return validateSession(sessionId) != null;
    }

    /**
     * セッション削除
     */
    @SuppressWarnings("unused")
    public void deleteSession(String sessionId) {
        try (PreparedStatement stmt = getConnection().prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                AppLogger.info("セッション削除: " + sessionId);
            }
        } catch (Exception e) {
            AppLogger.error("セッション削除失敗: " + e.getMessage());
        }
    }

    /**
     * 期限切れセッションのクリーンアップ（DB）
     */
    public void cleanupExpiredSessions() {
        try (PreparedStatement stmt = getConnection().prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
            stmt.setObject(1, LocalDateTime.now());
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                AppLogger.info("期限切れセッションを" + deleted + "件削除しました");
            }
        } catch (Exception e) {
            AppLogger.error("セッションクリーンアップ中にエラー: " + e.getMessage());
        }
    }

    /**
     * ログイン履歴をlogin_historyテーブルに記録
     * @param username ユーザー名
     * @param success 成功/失敗フラグ
     * @param ipAddress ログイン元IPアドレス
     * @param userAgent ユーザーエージェント
     */
    public void insertLoginHistory(String username, boolean success, String ipAddress, String userAgent) {
        String sql = "INSERT INTO login_history (user_id, login_time, ip_address, user_agent, success) " +
                "SELECT id, NOW(), ?, ?, ? FROM users WHERE username = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, ipAddress != null ? ipAddress : "");
            stmt.setString(2, userAgent != null ? userAgent : "");
            stmt.setBoolean(3, success);
            stmt.setString(4, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            AppLogger.warn("ログイン履歴記録失敗: " + e.getMessage());
        }
    }

    /**
     * ログイン失敗を記録し、一定時間内の回数超過でブロック登録する
     * @param username ユーザー名
     * @param ipAddress クライアントIP
     * @param userAgent UA
     */
    private void recordLoginFailure(String username, String ipAddress, String userAgent) {
        insertLoginHistory(username, false, ipAddress, userAgent);
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        try {
            if (isActiveLoginBlockExists(ipAddress)) {
                return;
            }
            if (countRecentLoginFailures(ipAddress) >= LOGIN_FAIL_THRESHOLD) {
                registerLoginBlock(ipAddress);
            }
        } catch (SQLException e) {
            AppLogger.error("ログイン失敗回数判定エラー: " + e.getMessage());
        }
    }

    /**
     * 指定IPの直近失敗回数を取得
     * @param ipAddress クライアントIP
     * @return 失敗回数
     * @throws SQLException SQL例外
     */
    private int countRecentLoginFailures(String ipAddress) throws SQLException {
        String sql = "SELECT COUNT(*) FROM login_history WHERE success = FALSE AND ip_address = ? AND login_time >= DATE_SUB(NOW(), INTERVAL ? MINUTE)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, ipAddress);
            stmt.setLong(2, LOGIN_FAIL_WINDOW_MINUTES);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    /**
     * すでにAPP_LOGIN用途のアクティブなブロックが存在するか判定
     * @param ipAddress クライアントIP
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    private boolean isActiveLoginBlockExists(String ipAddress) throws SQLException {
        byte[] ipBytes = toIpBytes(ipAddress);
        if (ipBytes == null) {
            return false;
        }
        String sql = "SELECT 1 FROM block_ip WHERE ip_address = ? AND service_type = 'APP_LOGIN' AND status = 'ACTIVE' AND (end_at IS NULL OR end_at > NOW()) LIMIT 1";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setBytes(1, ipBytes);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * ログイン失敗回数超過によりブロックIPへ登録
     * @param ipAddress クライアントIP
     */
    private void registerLoginBlock(String ipAddress) {
        byte[] ipBytes = toIpBytes(ipAddress);
        if (ipBytes == null) {
            return;
        }
        String sql = "INSERT INTO block_ip (ip_address, service_type, target_agent_name, reason, start_at, end_at, status, created_by, updated_by) " +
                "VALUES (?, 'APP_LOGIN', NULL, ?, NOW(), DATE_ADD(NOW(), INTERVAL ? MINUTE), 'ACTIVE', ?, ?)";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setBytes(1, ipBytes);
            stmt.setString(2, "ログイン失敗が5回/5分に到達したため自動ブロック");
            stmt.setLong(3, AUTO_BLOCK_DURATION_MINUTES);
            stmt.setString(4, "system");
            stmt.setString(5, "system");
            stmt.executeUpdate();
            AppLogger.warn("ログイン失敗多発によりIPをブロック: " + ipAddress);

            // 最短end_atを再取得し、+1分でクリーンアップを再スケジュール
            scheduleNextBlockIpCleanupFromDatabase();
         } catch (SQLException e) {
             AppLogger.error("ブロックIP登録エラー: " + e.getMessage());
         }
     }

    /**
     * block_ipの終了予定に合わせてクリーンアップを一度だけ予約
     * @param runAt 実行予定時刻（end_at+α）
     */
    private void scheduleBlockIpCleanup(LocalDateTime runAt) {
        if (runAt == null) return;
        long delayMillis = java.time.Duration.between(LocalDateTime.now(), runAt).toMillis();
        if (delayMillis < 0) delayMillis = 0;
        // 既存スケジュールがあればキャンセルし、最短end_atのみを予約
        if (blockIpCleanupFuture != null && !blockIpCleanupFuture.isDone()) {
            blockIpCleanupFuture.cancel(false);
        }
        blockIpCleanupFuture = blockIpScheduler.schedule(() -> {
            try {
                runBlockIpCleanupAndReschedule();
                AppLogger.info("ブロックIP期限到来に伴いブロックIPクリーンアップバッチを実行");
            } catch (Exception e) {
                AppLogger.error("ブロックIPクリーンアップ予約実行エラー: " + e.getMessage());
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * ブロックIPクリーンアップを実行し、完了後に次の最短end_atで再スケジュール
     */
    private void runBlockIpCleanupAndReschedule() {
        runBlockIpCleanupBatch();
    }

    /**
     * 起動時にDBから最短end_atを取得し、クリーンアップを一度予約
     */
    private void scheduleNextBlockIpCleanupFromDatabase() {
        String sql = "SELECT MIN(end_at) AS next_end FROM block_ip WHERE status = 'ACTIVE' AND end_at IS NOT NULL AND end_at > NOW()";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                var next = rs.getTimestamp("next_end");
                if (next != null) {
                    LocalDateTime nextEnd = next.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                    scheduleBlockIpCleanup(nextEnd.plusMinutes(CLEANUP_OFFSET_MINUTES));
                }
            }
        } catch (SQLException e) {
            AppLogger.warn("起動時ブロックIPクリーンアップ予約の取得に失敗: " + e.getMessage());
        }
    }

    /**
     * 文字列表現のIPをVARBINARY(16)向けのバイト配列に変換
     * @param ipAddress クライアントIP
     * @return バイト配列（変換不可時はnull）
     */
    private byte[] toIpBytes(String ipAddress) {
        try {
            return InetAddress.getByName(ipAddress).getAddress();
        } catch (UnknownHostException e) {
            AppLogger.warn("IPアドレス変換失敗: " + ipAddress + " / " + e.getMessage());
            return null;
        }
    }

    /**
     * セッション情報クラス
     */
    public static class SessionInfo {
        private final String username;
        private final String sessionId;
        private final LocalDateTime expiresAt;
        private final boolean mustChangePassword;

        /**
         * コンストラクタ
         * @param username ユーザー名
         * @param sessionId セッションID
         * @param expiresAt 有効期限
         * @param mustChangePassword パスワード変更が必要か
         */
        public SessionInfo(String username, String sessionId, LocalDateTime expiresAt, boolean mustChangePassword) {
            this.username = username;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
            this.mustChangePassword = mustChangePassword;
        }

        /**
         * ユーザー名を取得
         */
        public String getUsername() { return username; }

        /**
         * セッションIDを取得
         */
        public String getSessionId() { return sessionId; }

        /**
         * 有効期限を取得
         */
        public LocalDateTime getExpiresAt() { return expiresAt; }

        /**
         * パスワード変更が必要か
         */
        public boolean isMustChangePassword() { return mustChangePassword; }

        /**
         * セッションが期限切れか判定
         * @return 期限切れならtrue
         */
        public boolean isExpired() {
            return expiresAt == null || LocalDateTime.now().isAfter(expiresAt);
        }
    }

    /**
     * 現在のパスワードが一致するか検証
     * @param username ユーザー名
     * @param plainPassword 平文パスワード
     * @return 一致する場合true
     */
    @SuppressWarnings("unused")
    public boolean verifyPassword(String username, String plainPassword) {
        if (username == null || plainPassword == null) return false;
        String sql = "SELECT password_hash FROM users WHERE username = ? AND is_active = TRUE";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                return passwordEncoder.matches(plainPassword, storedHash);
            }
        } catch (SQLException e) {
            AppLogger.error("verifyPassword失敗: " + e.getMessage());
        }
        return false;
    }

    /**
     * セッションをログアウト（削除）
     * @param sessionId セッションID
     */
    public void logout(String sessionId) {
        try (PreparedStatement stmt = getConnection().prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            int deleted = stmt.executeUpdate();
            if (deleted > 0) {
                AppLogger.info("セッションログアウト: " + sessionId + " を削除");
            }
        } catch (Exception e) {
            AppLogger.error("ログアウト処理エラー: " + e.getMessage());
        }
    }

    /**
     * セッションIDからユーザー名を取得
     * @param sessionId セッションID
     * @return ユーザー名（無効��場合はnull）
     */
    public String getUsernameBySessionId(String sessionId) {
        if (sessionId == null) return null;
        String sql = "SELECT username, expires_at FROM sessions WHERE session_id = ?";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, sessionId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                LocalDateTime expiresAt = rs.getTimestamp("expires_at").toLocalDateTime();
                if (LocalDateTime.now().isBefore(expiresAt)) {
                    return rs.getString("username");
                }
            }
        } catch (SQLException e) {
            AppLogger.error("セッションからユーザー名取得失敗: " + e.getMessage());
        }
        return null;
    }

    /**
     * サービスのリソースを解放し、スケジューラを安全に停止する
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
        if (blockIpScheduler != null && !blockIpScheduler.isShutdown()) {
            blockIpScheduler.shutdown();
        }
    }
}
