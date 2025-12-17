package com.edamame.web.security;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import static com.edamame.security.db.DbService.*;
import com.edamame.security.tools.AppLogger;

/**
 * 認証サービスクラス
 * ユーザー認証とセッション管理を担当
 */
public class AuthenticationService {

    private final BCryptPasswordEncoder passwordEncoder;
    private final ScheduledExecutorService scheduler;

    // セッション有効期限（デフォルト: 24時間）
    private static final long SESSION_TIMEOUT_HOURS = 24;

    /**
     * コンストラクタ
     */
    public AuthenticationService() {
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.scheduler = Executors.newScheduledThreadPool(1);

        // 期限切れセッションのクリーンアップを1時間ごとに実行
        // this-escape警告回避：匿名クラスを使用
        Runnable cleanupTask = new Runnable() {
            @Override
            public void run() {
                cleanupExpiredSessions();
            }
        };
        scheduler.scheduleAtFixedRate(cleanupTask, 1, 1, java.util.concurrent.TimeUnit.HOURS);
    }



    /**
     * ユーザー認証を実行（IPアドレス・User-Agent対応）
     * @param username ユーザー名
     * @param password パスワード
     * @param rememberMe ログイン状態維持フラグ
     * @param ipAddress クライアントIPアドレス
     * @param userAgent ユーザーエージェント
     * @return セッションID（認証失敗時はnull）
     */
    public String authenticate(String username, String password, boolean rememberMe, String ipAddress, String userAgent) {
        if (username == null || password == null) {
            return null;
        }
        String sql = "SELECT password_hash FROM users WHERE username = ? AND is_active = TRUE";
        try (PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                if (passwordEncoder.matches(password, storedHash)) {
                    String sessionId = createSession(username, rememberMe);
                    insertLoginHistory(username, true, ipAddress, userAgent);
                    AppLogger.info("ユーザー認証成功: " + username);
                    return sessionId;
                }
            }
            insertLoginHistory(username, false, ipAddress, userAgent);
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
        try (PreparedStatement stmt = getConnection().prepareStatement("SELECT username, expires_at FROM sessions WHERE session_id = ?")) {
            stmt.setString(1, sessionId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    LocalDateTime expiresAt = rs.getObject("expires_at", LocalDateTime.class);
                    if (expiresAt != null && expiresAt.isAfter(LocalDateTime.now())) {
                        String username = rs.getString("username");
                        AppLogger.debug("セッション認証成功: " + username + " (" + sessionId + ")");
                        return new SessionInfo(username, sessionId, expiresAt);
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
    public boolean isValidSession(String sessionId) {
        return validateSession(sessionId) != null;
    }

    /**
     * セッション削除
     */
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
     * セッション情報クラス
     */
    public static class SessionInfo {
        private final String username;
        private final String sessionId;
        private final LocalDateTime expiresAt;

        /**
         * コンストラクタ
         * @param username ユーザー名
         * @param sessionId セッションID
         * @param expiresAt 有効期限
         */
        public SessionInfo(String username, String sessionId, LocalDateTime expiresAt) {
            this.username = username;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
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
         * セッションが期限切れか判定
         */
        public boolean isExpired() {
            return expiresAt == null || LocalDateTime.now().isAfter(expiresAt);
        }
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
    }
}

