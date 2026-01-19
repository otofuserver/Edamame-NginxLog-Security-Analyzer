package com.edamame.web.service;

import com.edamame.security.db.DbService;
import com.edamame.security.tools.AppLogger;
import com.edamame.web.security.WebSecurityUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * URL抑止条件のCRUDと検索を担当するサービスクラス。
 * 正規表現パターンをサーバー単位または全体で管理し、UI向けに一覧・更新機能を提供する。
 */
public class UrlSuppressionService {

    private static final List<String> ALLOWED_SORT = List.of("updated_at", "created_at", "server_name", "url_pattern", "is_enabled", "last_access_at", "drop_count");

    /**
     * URL抑止条件の一覧を検索する。
     * @param q キーワード（パターン・説明に対してLIKE検索）
     * @param serverName 対象サーバー（"all"で全体、null/空で全件）
     * @param sort ソートキー
     * @param order asc/desc
     * @return 抑止条件のリスト
     */
    public List<Map<String, Object>> search(String q, String serverName, String sort, String order) {
        return search(q, serverName, sort, order, 1, 20).items();
    }

    public SearchResult search(String q, String serverName, String sort, String order, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
         String safeSort = ALLOWED_SORT.contains(sort) ? sort : "updated_at";
         String safeOrder = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";
         List<Map<String, Object>> items = new ArrayList<>();
         StringBuilder sql = new StringBuilder();
         sql.append("SELECT id, server_name, url_pattern, description, is_enabled, last_access_at, drop_count, created_by, updated_by, created_at, updated_at \n")
             .append("FROM url_suppressions WHERE 1=1 ");
         List<Object> params = new ArrayList<>();
         StringBuilder where = new StringBuilder();
         if (serverName != null && !serverName.isBlank()) {
            where.append(" AND (server_name COLLATE utf8mb4_unicode_ci = ? OR server_name = 'all')");
            params.add(serverName.trim());
         }
         if (q != null && !q.isBlank()) {
             String like = "%" + q.trim() + "%";
            where.append(" AND (url_pattern LIKE ? OR description LIKE ?)");
             params.add(like);
             params.add(like);
         }
         String whereClause = where.toString();
         sql.append(whereClause);
         sql.append(" ORDER BY ").append(safeSort).append(" ").append(safeOrder).append(", id DESC")
            .append(" LIMIT ? OFFSET ?");
         try (Connection conn = DbService.getConnection()) {
            // 件数カウント
            long total = countTotal(conn, whereClause, params);
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                for (Object p : params) {
                    ps.setObject(idx++, p);
                }
                ps.setInt(idx++, safeSize);
                ps.setInt(idx, (safePage - 1) * safeSize);
             try (ResultSet rs = ps.executeQuery()) {
                 while (rs.next()) {
                     Map<String, Object> row = new HashMap<>();
                     row.put("id", rs.getLong("id"));
                     row.put("serverName", rs.getString("server_name"));
                     row.put("urlPattern", rs.getString("url_pattern"));
                     row.put("description", rs.getString("description"));
                     row.put("isEnabled", rs.getBoolean("is_enabled"));
                     row.put("lastAccessAt", toLocal(rs.getTimestamp("last_access_at")));
                     row.put("dropCount", rs.getLong("drop_count"));
                     row.put("createdBy", rs.getString("created_by"));
                     row.put("updatedBy", rs.getString("updated_by"));
                     row.put("createdAt", toLocal(rs.getTimestamp("created_at")));
                     row.put("updatedAt", toLocal(rs.getTimestamp("updated_at")));
                     items.add(row);
                 }
             }
            }
            long totalPages = (long)Math.ceil((double) total / safeSize);
            return new SearchResult(items, total, safePage, safeSize, totalPages == 0 ? 1 : totalPages);
         } catch (SQLException e) {
             AppLogger.error("URL抑止条件検索に失敗: " + e.getMessage());
         }
        return new SearchResult(items, 0, safePage, safeSize, 1);
     }

    /**
     * 新規の抑止条件を登録する。
     * @param serverName サーバー名またはall
     * @param pattern 正規表現
     * @param description 説明
     * @param enabled 有効/無効
     * @param username 操作ユーザー
     * @return 追加件数
     * @throws SQLException SQL例外
     */
    public int create(String serverName, String pattern, String description, boolean enabled, String username) throws SQLException {
        String sql = "INSERT INTO url_suppressions (server_name, url_pattern, description, is_enabled, created_by, updated_by) VALUES (?,?,?,?,?,?)";
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeServer(serverName));
            ps.setString(2, pattern);
            ps.setString(3, description);
            ps.setBoolean(4, enabled);
            ps.setString(5, username);
            ps.setString(6, username);
            return ps.executeUpdate();
        }
    }

    /**
     * 既存の抑止条件を更新する。
     * @param id ID
     * @param serverName サーバー名
     * @param pattern 正規表現
     * @param description 説明
     * @param enabled 有効/無効
     * @param username 操作ユーザー
     * @return 更新件数
     * @throws SQLException SQL例外
     */
    public int update(long id, String serverName, String pattern, String description, boolean enabled, String username) throws SQLException {
        String sql = "UPDATE url_suppressions SET server_name = ?, url_pattern = ?, description = ?, is_enabled = ?, updated_by = ?, updated_at = NOW() WHERE id = ?";
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeServer(serverName));
            ps.setString(2, pattern);
            ps.setString(3, description);
            ps.setBoolean(4, enabled);
            ps.setString(5, username);
            ps.setLong(6, id);
            return ps.executeUpdate();
        }
    }

    /**
     * 抑止条件を削除する。
     * @param id ID
     * @return 削除件数
     * @throws SQLException SQL例外
     */
    public int delete(long id) throws SQLException {
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM url_suppressions WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate();
        }
    }

    /**
     * 有効・無効を切り替える。
     * @param id ID
     * @param enabled 有効にするか
     * @param username 操作ユーザー
     * @return 更新件数
     * @throws SQLException SQL例外
     */
    public int toggleEnabled(long id, boolean enabled, String username) throws SQLException {
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE url_suppressions SET is_enabled = ?, updated_by = ?, updated_at = NOW() WHERE id = ?")) {
            ps.setBoolean(1, enabled);
            ps.setString(2, username);
            ps.setLong(3, id);
            return ps.executeUpdate();
        }
    }

    /**
     * IDで1件取得する。
     * @param id ID
     * @return 該当レコード
     */
    public Map<String, Object> findById(long id) {
        String sql = "SELECT id, server_name, url_pattern, description, is_enabled FROM url_suppressions WHERE id = ?";
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", rs.getLong("id"));
                    map.put("serverName", rs.getString("server_name"));
                    map.put("urlPattern", rs.getString("url_pattern"));
                    map.put("description", rs.getString("description"));
                    map.put("isEnabled", rs.getBoolean("is_enabled"));
                    return map;
                }
            }
        } catch (SQLException e) {
            AppLogger.error("URL抑止条件取得に失敗: " + e.getMessage());
        }
        return null;
    }

    private String normalizeServer(String serverName) {
        if (serverName == null || serverName.isBlank()) return "all";
        return WebSecurityUtils.sanitizeInput(serverName.trim());
    }

    private LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private long countTotal(Connection conn, String whereClause, List<Object> params) throws SQLException {
        StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM url_suppressions WHERE 1=1 ");
        countSql.append(whereClause);
        try (PreparedStatement cps = conn.prepareStatement(countSql.toString())) {
            for (int i = 0; i < params.size(); i++) cps.setObject(i + 1, params.get(i));
            try (ResultSet crs = cps.executeQuery()) {
                if (crs.next()) return crs.getLong(1);
            }
        }
        return 0;
    }

    public record SearchResult(List<Map<String, Object>> items, long total, int page, int size, long totalPages) {}
 }
