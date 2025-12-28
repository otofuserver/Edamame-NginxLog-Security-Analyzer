package com.edamame.web.service.impl;

import com.edamame.web.dto.UserDto;
import com.edamame.web.dto.PageResult;
import com.edamame.web.service.UserService;
import com.edamame.security.tools.AppLogger;
import static com.edamame.security.db.DbService.getConnection;

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
        java.util.List<java.util.Map<String, String>> list = new java.util.ArrayList<>();
        if (username == null) return list;
        if (limit <= 0) limit = 20;
        String sql = "SELECT login_time, ip_address FROM login_history lh JOIN users u ON lh.user_id = u.id WHERE u.username = ? ORDER BY lh.login_time DESC LIMIT ?";
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        java.util.Map<String, String> m = new java.util.HashMap<>();
                        java.sql.Timestamp ts = rs.getTimestamp("login_time");
                        m.put("login_time", ts == null ? "" : ts.toLocalDateTime().toString());
                        try { m.put("ip", rs.getString("ip_address")); } catch (Exception e) { m.put("ip", ""); }
                        list.add(m);
                    }
                }
                return list;
            } catch (SQLException e) {
                AppLogger.warn("getLoginHistory SQLエラー (試行:" + attempt + "): " + e.getMessage());
                try { Thread.sleep(100 * attempt); } catch (InterruptedException ignored) {}
            }
        }
        AppLogger.error("getLoginHistory に失敗しました: 最大試行回数到達");
        return list;
    }
}
