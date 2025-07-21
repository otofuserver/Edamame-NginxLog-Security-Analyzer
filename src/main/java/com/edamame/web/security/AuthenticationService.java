package com.edamame.web.security;

import com.edamame.web.security.BCryptPasswordEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 認証サービスクラス
 * ユーザー認証とセッション管理を担当
 */
public class AuthenticationService {

    private final Connection dbConnection;
    private final BiConsumer<String, String> logFunction;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ConcurrentHashMap<String, SessionInfo> activeSessions;

    // セッション有効期限（デフォルト: 24時間）
    private static final long SESSION_TIMEOUT_HOURS = 24;

    /**
     * セッション情報クラス
     */
    public static class SessionInfo {
        private final String sessionId;
        private final String username;
        private final LocalDateTime createdAt;
        private final LocalDateTime expiresAt;
        private final boolean rememberMe;

        public SessionInfo(String sessionId, String username, boolean rememberMe) {
            this.sessionId = sessionId;
            this.username = username;
            this.createdAt = LocalDateTime.now();
            this.rememberMe = rememberMe;

            // "ログインしたままにする"の場合は30日、そうでなければ24時間
            long timeoutHours = rememberMe ? 24 * 30 : SESSION_TIMEOUT_HOURS;
            this.expiresAt = createdAt.plusHours(timeoutHours);
        }

        public String getSessionId() { return sessionId; }
        public String getUsername() { return username; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public boolean isRememberMe() { return rememberMe; }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expiresAt);
        }
    }

    /**
     * コンストラクタ
     * @param dbConnection データベース接続
     * @param logFunction ログ出力関数
     */
    public AuthenticationService(Connection dbConnection, BiConsumer<String, String> logFunction) {
        this.dbConnection = dbConnection;
        this.logFunction = logFunction;
        this.passwordEncoder = new BCryptPasswordEncoder();
        this.activeSessions = new ConcurrentHashMap<>();

        // データベースにユーザーテーブルが存在しない場合は作成
        initializeUserTable();
    }

    /**
     * ユーザーテーブルの初期化
     */
    private void initializeUserTable() {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS web_users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(50) UNIQUE NOT NULL,
                password_hash VARCHAR(255) NOT NULL,
                is_active BOOLEAN DEFAULT TRUE,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                last_login TIMESTAMP NULL
            )
        """;

        try (PreparedStatement stmt = dbConnection.prepareStatement(createTableSql)) {
            stmt.executeUpdate();
            logFunction.accept("web_usersテーブルを初期化しました", "INFO");

            // デフォルトユーザーが存在しない場合は作成
            createDefaultUserIfNotExists();

        } catch (SQLException e) {
            logFunction.accept("ユーザーテーブルの初期化に失敗: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * デフォルトユーザーの作成（初回セットアップ用）
     */
    private void createDefaultUserIfNotExists() {
        String checkUserSql = "SELECT COUNT(*) FROM web_users WHERE username = ?";
        String insertUserSql = "INSERT INTO web_users (username, password_hash) VALUES (?, ?)";

        try {
            // 既存ユーザーの確認
            try (PreparedStatement checkStmt = dbConnection.prepareStatement(checkUserSql)) {
                checkStmt.setString(1, "admin");
                ResultSet rs = checkStmt.executeQuery();

                if (rs.next() && rs.getInt(1) == 0) {
                    // デフォルトユーザー「admin/admin123」を作成
                    try (PreparedStatement insertStmt = dbConnection.prepareStatement(insertUserSql)) {
                        insertStmt.setString(1, "admin");
                        insertStmt.setString(2, passwordEncoder.encode("admin123"));
                        insertStmt.executeUpdate();

                        logFunction.accept("デフォルトユーザー（admin/admin123）を作成しました", "INFO");
                        logFunction.accept("本番環境では必ずパスワードを変更してください", "WARN");
                    }
                }
            }
        } catch (SQLException e) {
            logFunction.accept("デフォルトユーザーの作成に失敗: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * ユーザー認証を実行
     * @param username ユーザー名
     * @param password パスワード
     * @param rememberMe ログインしたままにするか
     * @return 認証成功時はセッションID、失敗時はnull
     */
    public String authenticate(String username, String password, boolean rememberMe) {
        if (username == null || password == null || username.trim().isEmpty()) {
            return null;
        }

        String sql = "SELECT password_hash FROM web_users WHERE username = ? AND is_active = TRUE";

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, username.trim());
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String storedHash = rs.getString("password_hash");

                if (passwordEncoder.matches(password, storedHash)) {
                    // 認証成功 - セッションを作成
                    String sessionId = createSession(username, rememberMe);
                    updateLastLogin(username);

                    logFunction.accept("ユーザー認証成功: " + username, "INFO");
                    return sessionId;
                }
            }

            logFunction.accept("ユーザー認証失敗: " + username, "WARN");
            return null;

        } catch (SQLException e) {
            logFunction.accept("認証処理でエラー: " + e.getMessage(), "ERROR");
            return null;
        }
    }

    /**
     * セッションを作成
     * @param username ユーザー名
     * @param rememberMe ログインしたままにするか
     * @return セッションID
     */
    private String createSession(String username, boolean rememberMe) {
        String sessionId = UUID.randomUUID().toString();
        SessionInfo sessionInfo = new SessionInfo(sessionId, username, rememberMe);

        activeSessions.put(sessionId, sessionInfo);

        logFunction.accept("セッション作成: " + username + " (rememberMe: " + rememberMe + ")", "DEBUG");
        return sessionId;
    }

    /**
     * セッションの有効性を確認
     * @param sessionId セッションID
     * @return 有効な場合はSessionInfo、無効な場合はnull
     */
    public SessionInfo validateSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }

        SessionInfo sessionInfo = activeSessions.get(sessionId);

        if (sessionInfo == null) {
            return null;
        }

        if (sessionInfo.isExpired()) {
            activeSessions.remove(sessionId);
            logFunction.accept("期限切れセッションを削除: " + sessionInfo.getUsername(), "DEBUG");
            return null;
        }

        return sessionInfo;
    }

    /**
     * ログアウト処理
     * @param sessionId セッションID
     */
    public void logout(String sessionId) {
        SessionInfo sessionInfo = activeSessions.remove(sessionId);
        if (sessionInfo != null) {
            logFunction.accept("ログアウト: " + sessionInfo.getUsername(), "INFO");
        }
    }

    /**
     * 最終ログイン時刻を更新
     * @param username ユーザー名
     */
    private void updateLastLogin(String username) {
        String sql = "UPDATE web_users SET last_login = CURRENT_TIMESTAMP WHERE username = ?";

        try (PreparedStatement stmt = dbConnection.prepareStatement(sql)) {
            stmt.setString(1, username);
            stmt.executeUpdate();
        } catch (SQLException e) {
            logFunction.accept("最終ログイン時刻の更新に失敗: " + e.getMessage(), "WARN");
        }
    }

    /**
     * 期限切れ��ッションのクリーンアップ
     */
    public void cleanupExpiredSessions() {
        activeSessions.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                logFunction.accept("期限切れセッションをクリーンアップ: " + entry.getValue().getUsername(), "DEBUG");
                return true;
            }
            return false;
        });
    }

    /**
     * アクティブセッション数を取得
     * @return アクティブセッション数
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }
}
