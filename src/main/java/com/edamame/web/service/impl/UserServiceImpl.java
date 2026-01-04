package com.edamame.web.service.impl;

import com.edamame.web.dto.UserDto;
import com.edamame.web.dto.PageResult;
import com.edamame.web.service.UserService;
import com.edamame.security.tools.AppLogger;
import static com.edamame.security.db.DbService.getConnection;

import com.edamame.security.action.MailActionHandler;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.edamame.web.exception.AdminRetentionException;

/**
 * UserServiceの実装
 */
public class UserServiceImpl implements UserService {

    private static final int MAX_RETRIES = 5;

    public UserServiceImpl() {}

    @Override
    public PageResult<UserDto> searchUsers(String q, int page, int size) {
        List<UserDto> results = new ArrayList<>();
        if (page <= 0) page = 1;
        if (size <= 0) size = 20;
        if (size > 100) size = 100;
        int offset = (page - 1) * size;

        String like = "%" + (q == null ? "" : q.trim().replace("%","\\%")) + "%";

        // login_history から直近ログインをサブクエリで取得する（users テーブルに last_login カラムがない環境対応）
        String sql = "SELECT u.id, u.username, u.email, (SELECT MAX(login_time) FROM login_history lh WHERE lh.user_id = u.id) AS last_login, u.is_active " +
                "FROM users u " +
                "WHERE u.username LIKE ? OR u.email LIKE ? " +
                "ORDER BY u.username ASC " +
                "LIMIT ? OFFSET ?";

        String countSql = "SELECT COUNT(*) FROM users u WHERE u.username LIKE ? OR u.email LIKE ?";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setInt(3, size);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Long id = null;
                        try { id = rs.getLong("id"); } catch (Exception ignored) {}
                        String username = rs.getString("username");
                        String email = rs.getString("email");
                        LocalDateTime lastLogin = null;
                        try { java.sql.Timestamp ts = rs.getTimestamp("last_login"); if (ts != null) lastLogin = ts.toLocalDateTime(); } catch (Exception ignored) {}
                        boolean enabled = rs.getBoolean("is_active");
                        results.add(new UserDto(id, username, email, lastLogin, enabled));
                    }
                }
                // count
                long total = 0;
                try (PreparedStatement cps = getConnection().prepareStatement(countSql)) {
                    cps.setString(1, like);
                    cps.setString(2, like);
                    try (ResultSet crs = cps.executeQuery()) {
                        if (crs.next()) total = crs.getLong(1);
                    }
                }
                return new PageResult<>(total, page, size, results);
            } catch (SQLException e) {
                AppLogger.warn("ユーザー検索SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("ユーザー検索に失敗しました: 最大試行回数到達");
        return new PageResult<>(0, page, size, results);
    }

    @Override
    public Optional<UserDto> findByUsername(String username) {
        String sql = "SELECT u.id, u.username, u.email, (SELECT MAX(login_time) FROM login_history lh WHERE lh.user_id = u.id) AS last_login, u.is_active FROM users u WHERE u.username = ? LIMIT 1";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Long id = null;
                        try { id = rs.getLong("id"); } catch (Exception ignored) {}
                        String email = rs.getString("email");
                        LocalDateTime lastLogin = null;
                        try { java.sql.Timestamp ts = rs.getTimestamp("last_login"); if (ts != null) lastLogin = ts.toLocalDateTime(); } catch (Exception ignored) {}
                        boolean enabled = rs.getBoolean("is_active");
                        return Optional.of(new UserDto(id, username, email, lastLogin, enabled));
                    }
                }
                return Optional.empty();
            } catch (SQLException e) {
                AppLogger.warn("findByUsername SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("findByUsernameに失敗しました: 最大試行回数到達");
        return Optional.empty();
    }

    @Override
    public boolean isAdmin(String username) {
        if (username == null) return false;
        String sql = "SELECT COUNT(*) FROM users_roles ur JOIN roles r ON ur.role_id = r.id JOIN users u ON ur.user_id = u.id WHERE u.username = ? AND r.role_name = 'admin'";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
                return false;
            } catch (SQLException e) {
                AppLogger.warn("isAdmin SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("isAdmin 判定に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public boolean updateUser(String username, String email, boolean enabled) {
        if (username == null) return false;
        // admin 保持チェック: 対象が admin であり、無効化しようとしている場合は他の有効な admin を確認
        try {
            if (!enabled) {
                // 対象が admin か確認
                if (isAdmin(username)) {
                    // 他に有効な admin が存在するか
                    String sql = "SELECT COUNT(*) FROM users_roles ur JOIN roles r ON ur.role_id = r.id JOIN users u ON ur.user_id = u.id WHERE r.role_name = 'admin' AND u.is_active = 1 AND u.username <> ?";
                    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                        try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                            ps.setString(1, username);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    if (rs.getInt(1) == 0) {
                                        throw new AdminRetentionException("最後の有効な admin を無効化できません");
                                    }
                                }
                            }
                            break;
                        } catch (SQLException e) {
                            AppLogger.warn("admin 保持チェックSQLエラー (試行:" + attempt + "): " + e.getMessage());
                            try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }
        } catch (AdminRetentionException ar) { throw ar; } catch (Exception ignore) {}
        String sql = "UPDATE users SET email = ?, is_active = ? WHERE username = ?";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, email);
                ps.setBoolean(2, enabled);
                ps.setString(3, username);
                int updated = ps.executeUpdate();
                return updated > 0;
            } catch (SQLException e) {
                AppLogger.warn("updateUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("updateUser に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public boolean deleteUser(String username) {
        if (username == null) return false;
        // 最後の有効な admin を削除しないチェック
        try {
            if (isAdmin(username)) {
                String sql = "SELECT COUNT(*) FROM users_roles ur JOIN roles r ON ur.role_id = r.id JOIN users u ON ur.user_id = u.id WHERE r.role_name = 'admin' AND u.is_active = 1 AND u.username <> ?";
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                        ps.setString(1, username);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                if (rs.getInt(1) == 0) {
                                    throw new AdminRetentionException("最後の有効な admin を削除できません");
                                }
                            }
                            break;
                        } catch (SQLException e) {
                            AppLogger.warn("admin 保持チェックSQLエラー (試行:" + attempt + "): " + e.getMessage());
                            try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }
        } catch (AdminRetentionException ar) { throw ar; } catch (Exception ignore) {}
        // 削除前にユーザーIDを取得し、関連テーブルを考慮して削除する（ここではシンプルに users テーブルから削除）
        String sql = "DELETE FROM users WHERE username = ?";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                int deleted = ps.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                AppLogger.warn("deleteUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("deleteUser に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public java.util.List<String> listAllRoles() {
        java.util.List<String> roles = new java.util.ArrayList<>();
        String sql = "SELECT role_name FROM roles ORDER BY role_name ASC";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) roles.add(rs.getString("role_name"));
                return roles;
            } catch (SQLException e) {
                AppLogger.warn("listAllRoles SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return roles;
    }

    @Override
    public java.util.List<String> getRolesForUser(String username) {
        java.util.List<String> roles = new java.util.ArrayList<>();
        if (username == null) return roles;
        String sql = "SELECT r.role_name FROM roles r JOIN users_roles ur ON r.id = ur.role_id JOIN users u ON ur.user_id = u.id WHERE u.username = ? ORDER BY r.role_name ASC";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) roles.add(rs.getString("role_name"));
                }
                return roles;
            } catch (SQLException e) {
                AppLogger.warn("getRolesForUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return roles;
    }

    @Override
    public boolean addRoleToUser(String username, String role) {
        if (username == null || role == null) return false;
        String findUserId = "SELECT id FROM users WHERE username = ? LIMIT 1";
        String findRoleId = "SELECT id FROM roles WHERE role_name = ? LIMIT 1";
        String insertSql = "INSERT INTO users_roles (user_id, role_id) VALUES (?, ?)";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement pus = getConnection().prepareStatement(findUserId); PreparedStatement prs = getConnection().prepareStatement(findRoleId)) {
                pus.setString(1, username);
                try (ResultSet urs = pus.executeQuery()) {
                    if (!urs.next()) return false;
                    long userId = urs.getLong(1);
                    prs.setString(1, role);
                    try (ResultSet rrs = prs.executeQuery()) {
                        if (!rrs.next()) return false;
                        long roleId = rrs.getLong(1);
                        try (PreparedStatement ins = getConnection().prepareStatement(insertSql)) {
                            ins.setLong(1, userId);
                            ins.setLong(2, roleId);
                            int v = ins.executeUpdate();
                            return v > 0;
                        }
                    }
                }
            } catch (SQLException e) {
                // Duplicate key の場合は既に存在するので true を返す
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate")) return true;
                AppLogger.warn("addRoleToUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("addRoleToUser に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public boolean removeRoleFromUser(String username, String role) {
        if (username == null || role == null) return false;
        // admin ロール削除時の保護: 最後の有効な admin を削除しない
        try {
            if ("admin".equals(role)) {
                String sql = "SELECT COUNT(*) FROM users_roles ur JOIN roles r ON ur.role_id = r.id JOIN users u ON ur.user_id = u.id WHERE r.role_name = 'admin' AND u.is_active = 1 AND u.username <> ?";
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                        ps.setString(1, username);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                if (rs.getInt(1) == 0) {
                                    throw new com.edamame.web.exception.AdminRetentionException("最後の有効な admin のロールを削除できません");
                                }
                            }
                            break;
                        } catch (SQLException e) {
                            AppLogger.warn("admin 保持チェックSQLエラー (試行:" + attempt + "): " + e.getMessage());
                            try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }
        } catch (com.edamame.web.exception.AdminRetentionException ar) { throw ar; } catch (Exception ignore) {}
        String sql = "DELETE ur FROM users_roles ur JOIN users u ON ur.user_id = u.id JOIN roles r ON ur.role_id = r.id WHERE u.username = ? AND r.role_name = ?";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setString(2, role);
                int deleted = ps.executeUpdate();
                return deleted > 0;
            } catch (SQLException e) {
                AppLogger.warn("removeRoleFromUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("removeRoleFromUser に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public boolean createUser(String username, String email, boolean enabled) {
        if (username == null || username.trim().isEmpty()) return false;
        String insertSql = "INSERT INTO users (username, email, password_hash, is_active) VALUES (?, ?, ?, ?)";
        // 生成パスワードはランダムだがフロントで必要であれば別エンドポイントで返す設計が良い
        try {
            final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*()-_";
            final int length = 12;
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
            String plain = sb.toString();
            String hashed = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, plain.toCharArray());
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try (PreparedStatement ps = getConnection().prepareStatement(insertSql)) {
                    ps.setString(1, username);
                    ps.setString(2, email);
                    ps.setString(3, hashed);
                    ps.setBoolean(4, enabled);
                    int v = ps.executeUpdate();
                    return v > 0;
                } catch (SQLException e) {
                    // 重複キー（username）の場合は明示的な例外を投げて上位で 409 を返せるようにする
                    String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                    if (msg.contains("duplicate") || msg.contains("unique") || msg.contains("duplicate entry")) {
                        AppLogger.warn("createUser 重複エントリ: " + e.getMessage());
                        throw new com.edamame.web.exception.DuplicateResourceException("username already exists", e);
                    }
                    AppLogger.warn("createUser SQLエラー (試行:" + attempt + "): " + e.getMessage());
                    try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            // RuntimeException（DuplicateResourceException 等）は上位で処理させる
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            AppLogger.warn("createUser 生成エラー: " + e.getMessage());
        }
        AppLogger.error("createUser に失敗しました: 最大試行回数到達または例外");
        return false;
    }

    /**
     * 新規ユーザーを作成し、初期パスワードを生成、必要に応じてアクティベーション用メールを送信する。
     * ここで activation_tokens テーブルへハッシュを保存し、メールを送信します。
     */
    @Override
    public String createUserWithActivation(String username, String email, boolean enabled) {
        if (username == null || username.trim().isEmpty()) return null;
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*()-_";
        final int length = 12;
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        String plain = sb.toString();
        String hashed;
        try { hashed = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, plain.toCharArray()); } catch (Exception e) { AppLogger.warn("createUserWithActivation ハッシュ生成エラー: " + e.getMessage()); return null; }

        String token = null;
        java.sql.Timestamp expires = null;
        if (!enabled) {
            token = java.util.UUID.randomUUID().toString();
            LocalDateTime exp = LocalDateTime.now().plusHours(24);
            expires = java.sql.Timestamp.valueOf(exp);
        }

        String insertUserSql = "INSERT INTO users (username, email, password_hash, is_active) VALUES (?, ?, ?, ?)";
        String findUserIdSql = "SELECT id FROM users WHERE username = ? LIMIT 1";
        String insertTokenSql = "INSERT INTO activation_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(insertUserSql)) {
                ps.setString(1, username);
                ps.setString(2, email);
                ps.setString(3, hashed);
                ps.setBoolean(4, enabled);
                int v = ps.executeUpdate();
                if (v <= 0) { AppLogger.warn("createUserWithActivation: users挿入が0件でした attempt=" + attempt); continue; }

                Long userId = null;
                try (PreparedStatement psId = getConnection().prepareStatement(findUserIdSql)) {
                    psId.setString(1, username);
                    try (ResultSet rs = psId.executeQuery()) { if (rs.next()) userId = rs.getLong(1); }
                } catch (SQLException e) { AppLogger.warn("createUserWithActivation: ユーザーID取得に失敗しました: " + e.getMessage()); }

                boolean tokenSaved = false;
                if (token != null && userId != null) {
                    String tokenHash = sha256Hex(token);
                    try (PreparedStatement tps = getConnection().prepareStatement(insertTokenSql)) {
                        tps.setLong(1, userId);
                        tps.setString(2, tokenHash);
                        tps.setTimestamp(3, expires);
                        int tv = tps.executeUpdate();
                        tokenSaved = tv > 0;
                    } catch (SQLException e) {
                        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                        if (msg.contains("unknown table") || msg.contains("doesn't exist") || msg.contains("no such table") || msg.contains("unknown column")) {
                            AppLogger.warn("createUserWithActivation: token保存に失敗（テーブル欠如?): " + e.getMessage());
                        } else {
                            AppLogger.warn("createUserWithActivation: token保存SQLエラー (試行:" + attempt + "): " + e.getMessage());
                            try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                        }
                    }
                }

                // メール送信（トークン保存失敗でもユーザーは作成済みなのでメールは送るが注意を明記）
                if (email != null && !email.isEmpty()) {
                    try {
                        MailActionHandler mailer = com.edamame.security.NginxLogToMysql.getSharedMailHandler();
                        if (mailer == null) {
                            mailer = new MailActionHandler();
                        }
                        String base = System.getenv("WEB_BASE_URL");
                        if (base == null || base.isEmpty()) base = "http://localhost:8080";
                        String activationUrl = token == null ? "" : base + "/api/activate?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
                        String subject = "[Edamame] アカウント有効化のご案内";
                        String body = "ユーザー名: " + username + "\n\n" + "初期パスワード: " + plain + "\n\n";
                        if (!enabled) {
                            if (tokenSaved) body += "以下のリンクをクリックしてアカウントを有効化してください（24時間有効）:\n" + activationUrl + "\n\n";
                            else body += "（警告）有効化リンクの保存に失敗したため、管理者による有効化が必要になる可能性があります。管理者に連絡してください。\n\n";
                        } else {
                            body += "アカウントはすでに有効になっています。ログイン後パスワードの変更を推奨します。\n\n";
                        }
                        body += "--\nEdamame Security Analyzer\n";
                        String res = mailer.sendToAddress("noreply@example.com", "Edamame Security Analyzer", email, subject, body);
                        AppLogger.log("createUserWithActivation: メール送信結果: " + res, "INFO");
                    } catch (Exception me) { AppLogger.log("createUserWithActivation メール送信に失敗しました: " + me.getMessage(), "ERROR"); }
                }

                try {
                    if (userId != null) {
                        String subj = "[Edamame] ユーザーの作成申請処理が実施されました";
                        String b = "ユーザー名: " + username + "\nユーザーID: " + userId + "\n\n新規ユーザーの作成申請が行われました。\n\n--\nEdamame Security Analyzer\n";
                        notifyAuditors(userId, username, subj, b);
                    }
                } catch (Exception e) { AppLogger.warn("createUserWithActivation: 監査通知の送信に失敗: " + e.getMessage()); }

                return plain;
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("duplicate") || msg.contains("unique") || msg.contains("duplicate entry")) {
                    AppLogger.warn("createUserWithActivation 重複エントリ: " + e.getMessage());
                    throw new com.edamame.web.exception.DuplicateResourceException("username already exists", e);
                }
                AppLogger.warn("createUserWithActivation SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("createUserWithActivation に失敗しました: 最大試行回数到達または例外");
        return null;
    }

    /**
     * トークンでユーザーを有効化する（activation_tokens を利用）
     */
    @Override
    public boolean activateUserByToken(String token) {
        if (token == null || token.trim().isEmpty()) return false;
        String selSql = "SELECT id, user_id, is_used, expires_at FROM activation_tokens WHERE token_hash = ? LIMIT 1";
        String updateUserSql = "UPDATE users SET is_active = 1 WHERE id = ?";
        String markUsedSql = "UPDATE activation_tokens SET is_used = TRUE WHERE id = ?";

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(selSql)) {
                ps.setString(1, sha256Hex(token));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) { AppLogger.log("activateUserByToken: トークンが見つかりません token=" + token, "WARN"); return false; }
                    long tokenId = rs.getLong("id");
                    long userId = rs.getLong("user_id");
                    boolean used = false; try { used = rs.getBoolean("is_used"); } catch (Exception ignored) {}
                    java.sql.Timestamp expiresAt = null; try { expiresAt = rs.getTimestamp("expires_at"); } catch (Exception ignored) {}

                    if (used) { AppLogger.log("activateUserByToken: トークンは既に使用済み token=" + token, "WARN"); return false; }
                    if (expiresAt != null && expiresAt.getTime() < System.currentTimeMillis()) { AppLogger.log("activateUserByToken: トークン期限切れ token=" + token, "WARN"); return false; }

                    String userEmail = null; String username = null;
                    try (PreparedStatement us = getConnection().prepareStatement("SELECT username, email FROM users WHERE id = ? LIMIT 1")) {
                        us.setLong(1, userId);
                        try (ResultSet urs = us.executeQuery()) { if (urs.next()) { username = urs.getString("username"); userEmail = urs.getString("email"); } }
                    } catch (SQLException e) { AppLogger.warn("activateUserByToken: ユーザー情報取得に失敗しました: " + e.getMessage()); }

                    try (PreparedStatement ups = getConnection().prepareStatement(updateUserSql)) { ups.setLong(1, userId); int updated = ups.executeUpdate(); if (updated <= 0) { AppLogger.log("activateUserByToken: 対応するユーザーが見つかりません userId=" + userId, "WARN"); return false; } }

                    try (PreparedStatement mps = getConnection().prepareStatement(markUsedSql)) { mps.setLong(1, tokenId); mps.executeUpdate(); }

                    AppLogger.log("activateUserByToken: トークン有効化成功 token=" + token, "INFO");

                    if (userEmail != null && !userEmail.isEmpty()) {
                        try {
                            MailActionHandler mailer = com.edamame.security.NginxLogToMysql.getSharedMailHandler();
                            if (mailer == null) {
                                mailer = new MailActionHandler();
                            }
                            String subject = "[Edamame] アカウントが有効化されました";
                            String body = (username == null ? "ユーザー" : username) + " 様\n\n" + "あなたのアカウントは正常に有効化されました。ログインしてサービスをご利用ください。\n\n" + "--\nEdamame Security Analyzer\n";
                            String res = mailer.sendToAddress("noreply@example.com", "Edamame Security Analyzer", userEmail, subject, body);
                            AppLogger.log("activateUserByToken: 有効化通知メール送信結果: " + res, "INFO");
                        } catch (Exception me) { AppLogger.warn("activateUserByToken: 有効化通知メールの送信に失敗しました: " + me.getMessage()); }
                    }

                    return true;
                }
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("unknown table") || msg.contains("doesn't exist") || msg.contains("no such table")) { AppLogger.warn("activateUserByToken: activation_tokens テーブルが見つかりません。フォールバックは廃止しました。token=" + token); return false; }
                AppLogger.warn("activateUserByToken SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("activateUserByToken に失敗しました: 最大試行回数到達");
        return false;
    }

    @Override
    public boolean hasExpiredUnusedActivationToken(String username) {
        if (username == null) return false;
        Long userId = null;
        String findUserSql = "SELECT id, is_active FROM users WHERE username = ? LIMIT 1";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(findUserSql)) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    userId = rs.getLong("id");
                    boolean isActive = false; try { isActive = rs.getBoolean("is_active"); } catch (Exception ignored) {}
                    if (isActive) return false;
                }
                break;
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("unknown table") || msg.contains("doesn't exist") || msg.contains("no such table")) { AppLogger.warn("hasExpiredUnusedActivationToken: activation_tokens テーブルが見つかりません: " + e.getMessage()); return false; }
                AppLogger.warn("hasExpiredUnusedActivationToken SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        if (userId == null) return false;

        String sql = "SELECT id FROM activation_tokens WHERE user_id = ? AND is_used = FALSE AND expires_at IS NOT NULL AND expires_at < CURRENT_TIMESTAMP ORDER BY created_at DESC LIMIT 1";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                if (msg.contains("unknown table") || msg.contains("doesn't exist") || msg.contains("no such table")) { AppLogger.warn("hasExpiredUnusedActivationToken: activation_tokens テーブルが見つかりません: " + e.getMessage()); return false; }
                AppLogger.warn("hasExpiredUnusedActivationToken SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    @Override
    public boolean resendActivationEmail(String username) {
        if (username == null) return false;
        long userId = -1L; String userEmail = null;
        String findSql = "SELECT id, email, is_active FROM users WHERE username = ? LIMIT 1";
        try (PreparedStatement ps = getConnection().prepareStatement(findSql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                userId = rs.getLong("id");
                userEmail = rs.getString("email");
                boolean isActive = false;
                try { isActive = rs.getBoolean("is_active"); } catch (Exception ignored) {}
                if (isActive) {
                    AppLogger.log("resendActivationEmail: ユーザーは既に有効です username=" + username, "INFO");
                    return false;
                }
            }
        } catch (SQLException e) {
            AppLogger.warn("resendActivationEmail: ユーザー検索でエラー: " + e.getMessage());
            return false;
        }

        if (userId <= 0 || userEmail == null || userEmail.trim().isEmpty()) return false;

        // 2) 新しいトークンを生成して保存
        String token = java.util.UUID.randomUUID().toString();
        String tokenHash = sha256Hex(token);
        java.sql.Timestamp expires = java.sql.Timestamp.valueOf(LocalDateTime.now().plusHours(24));
        String insertTokenSql = "INSERT INTO activation_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)";
        try (PreparedStatement tps = getConnection().prepareStatement(insertTokenSql)) {
            tps.setLong(1, userId); tps.setString(2, tokenHash); tps.setTimestamp(3, expires); int tv = tps.executeUpdate();
            if (tv <= 0) AppLogger.warn("resendActivationEmail: activation_tokens 挿入が0件でした user=" + username);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("unknown table") || msg.contains("doesn't exist") || msg.contains("no such table")) { AppLogger.warn("resendActivationEmail: activation_tokens テーブルが見つかりません: " + e.getMessage()); return false; }
            AppLogger.warn("resendActivationEmail: token保存SQLエラー: " + e.getMessage()); return false;
        }

        // 3) メール送信
        try {
            MailActionHandler mailer = com.edamame.security.NginxLogToMysql.getSharedMailHandler();
            if (mailer == null) {
                mailer = new MailActionHandler();
            }
            String base = System.getenv("WEB_BASE_URL"); if (base == null || base.isEmpty()) base = "http://localhost:8080";
            String activationUrl = base + "/api/activate?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
            String subject = "[Edamame] アカウント有効化のご案内";
            String body = "ユーザー名: " + username + "\n\n" + "アカウントを有効化するには以下のリンクをクリックしてください（24時間有効）:\n" + activationUrl + "\n\n--\nEdamame Security Analyzer\n";
            String res = mailer.sendToAddress("noreply@example.com", "Edamame Security Analyzer", userEmail, subject, body);
            AppLogger.log("resendActivationEmail: メール送信結果: " + res, "INFO");
            return res != null && res.startsWith("送信成功");
        } catch (Exception e) { AppLogger.warn("resendActivationEmail: メール送信エラー: " + e.getMessage()); return false; }
    }

    /**
     * auditor ロールおよびそれを継承する上位ロールに対して通知メールを送信する
     * @param targetUserId 対象ユーザーID
     * @param targetUsername 対象ユーザー名
     * @param subject メール件名
     * @param body メール本文
     */
    private void notifyAuditors(long targetUserId, String targetUsername, String subject, String body) {
        try {
            // ログと本文に対象ユーザー情報を付与してパラメータを使用する
            String targetInfo = "対象ユーザー: " + (targetUsername == null ? "(不明)" : targetUsername) + " (id=" + targetUserId + ")\n\n";
            AppLogger.log("notifyAuditors: 通知対象 -> " + targetInfo, "DEBUG");
            String bodyWithTarget = targetInfo + body;

            // MailActionHandler の既存ユーティリティを使って auditor と上位ロールへ送信
            // メインで生成した共有 MailActionHandler を流用（存在しない場合のみローカル生成）
            MailActionHandler mailer = com.edamame.security.NginxLogToMysql.getSharedMailHandler();
            if (mailer == null) {
                mailer = new MailActionHandler();
            }
            String fromEmail = "noreply@example.com";
            String fromName = "Edamame Security Analyzer";
            String res = mailer.sendToRoleIncludingHigherRoles("auditor", fromEmail, fromName, subject, bodyWithTarget);
            AppLogger.log("notifyAuditors: " + res, "INFO");
        } catch (Exception e) {
            AppLogger.warn("notifyAuditors: 監査通知送信で例外: " + e.getMessage());
        }
    }

    @Override
    public boolean resetPassword(String username, String plainPassword) {
        if (username == null || plainPassword == null) return false;
        // BCrypt でハッシュ化して保存
        try {
            String hashed = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, plainPassword.toCharArray());
            String sql = "UPDATE users SET password_hash = ? WHERE username = ?";
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                    ps.setString(1, hashed);
                    ps.setString(2, username);
                    int updated = ps.executeUpdate();
                    return updated > 0;
                } catch (SQLException e) {
                    AppLogger.warn("resetPassword SQLエラー (試行:" + attempt + "): " + e.getMessage());
                    try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            AppLogger.warn("resetPassword ハッシュ生成エラー: " + e.getMessage());
        }
        AppLogger.error("resetPassword に失敗しました: 最大試行回数到達または例外");
        return false;
    }

    @Override
    public String generateAndResetPassword(String username) {
        if (username == null) return null;
        // 生成ルール: 14文字、英大文字/小文字/数字/記号を混ぜる
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*()-_";
        final int length = 14;
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        String plain = sb.toString();
        // ハッシュ化して保存
        try {
            String hashed = at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, plain.toCharArray());
            String sql = "UPDATE users SET password_hash = ? WHERE username = ?";
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                    ps.setString(1, hashed);
                    ps.setString(2, username);
                    int updated = ps.executeUpdate();
                    if (updated > 0) return plain;
                    else return null;
                } catch (SQLException e) {
                    AppLogger.warn("generateAndResetPassword SQLエラー (試行:" + attempt + "): " + e.getMessage());
                    try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception e) {
            AppLogger.warn("generateAndResetPassword ハッシュ生成エラー: " + e.getMessage());
        }
        AppLogger.error("generateAndResetPassword に失敗しました");
        return null;
    }

    @Override
    public java.util.List<java.util.Map<String, String>> getLoginHistory(String username, int limit) {
        java.util.List<java.util.Map<String, String>> list = new java.util.ArrayList<>(); if (username == null) return list; if (limit <= 0) limit = 20;
        String sql = "SELECT login_time, ip_address FROM login_history lh JOIN users u ON lh.user_id = u.id WHERE u.username = ? ORDER BY lh.login_time DESC LIMIT ?";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username); ps.setInt(2, limit); try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { java.util.Map<String, String> m = new java.util.HashMap<>(); java.sql.Timestamp ts = rs.getTimestamp("login_time"); m.put("login_time", ts == null ? "" : ts.toLocalDateTime().toString()); try { m.put("ip", rs.getString("ip_address")); } catch (Exception e) { m.put("ip", ""); } list.add(m); }
                }
                return list;
            } catch (SQLException e) { AppLogger.warn("getLoginHistory SQLエラー (試行:" + attempt + "): " + e.getMessage()); try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {} }
        }
        AppLogger.error("getLoginHistory に失敗しました: 最大試行回数到達"); return list;
    }

    /**
     * 入力文字列のSHA-256ハッシュを16進文字列で返すユーティリティ
     */
    private static String sha256Hex(String input) {
        if (input == null) return null;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            AppLogger.warn("sha256Hex エラー: " + e.getMessage());
            return null;
        }
    }
}
