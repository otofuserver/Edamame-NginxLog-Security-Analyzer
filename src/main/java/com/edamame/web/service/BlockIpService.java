package com.edamame.web.service;

import com.edamame.security.db.DbService;
import com.edamame.security.tools.AppLogger;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * block_ipテーブルの検索・登録・削除を担当するサービス。
 */
public class BlockIpService {

    private static final List<String> ALLOWED_SORT = List.of("ip_address", "updated_at", "created_at", "start_at", "end_at", "status", "service_type");

    /**
     * ページング付き検索。
     */
    public SearchResult search(String status, String serviceType, String q, String sort, String order, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(100, size));
        String normalizedSort = normalizeSort(sort);
        String safeSort = ALLOWED_SORT.contains(normalizedSort) ? normalizedSort : "ip_address";
        String safeOrder = "desc".equalsIgnoreCase(order) ? "DESC" : "ASC";

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT id, ip_address, service_type, target_agent_name, reason, start_at, end_at, status, created_at, updated_at, created_by, updated_by FROM block_ip WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (serviceType != null && !serviceType.isBlank() && !"all".equalsIgnoreCase(serviceType)) {
            sql.append(" AND service_type = ?");
            params.add(serviceType.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (");
            sql.append("reason LIKE ? OR created_by LIKE ? OR updated_by LIKE ? OR INET6_NTOA(ip_address) LIKE ?");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            byte[] exactIp = toIpBytes(q.trim());
            if (exactIp != null) {
                sql.append(" OR ip_address = ?");
                params.add(exactIp);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY ").append(safeSort).append(" ").append(safeOrder).append(", id DESC LIMIT ? OFFSET ?");

        List<Map<String, Object>> items = new ArrayList<>();
        long total = 0;
        try (Connection conn = DbService.getConnection()) {
            if (conn == null) {
                throw new SQLException("DB connection is null");
            }
            total = countTotal(conn, status, serviceType, q);
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
                        row.put("ipAddress", toIpString(rs.getBytes("ip_address")));
                        row.put("serviceType", rs.getString("service_type"));
                        row.put("targetAgentName", rs.getString("target_agent_name"));
                        row.put("reason", rs.getString("reason"));
                        row.put("startAt", toLocal(rs.getTimestamp("start_at")));
                        row.put("endAt", toLocal(rs.getTimestamp("end_at")));
                        row.put("status", rs.getString("status"));
                        row.put("createdAt", toLocal(rs.getTimestamp("created_at")));
                        row.put("updatedAt", toLocal(rs.getTimestamp("updated_at")));
                        row.put("createdBy", rs.getString("created_by"));
                        row.put("updatedBy", rs.getString("updated_by"));
                        items.add(row);
                    }
                }
            }
        } catch (SQLException e) {
            AppLogger.error("block_ip検索に失敗: " + e.getMessage());
        }
        long totalPages = (long) Math.ceil((double) total / safeSize);
        return new SearchResult(items, total, safePage, safeSize, totalPages == 0 ? 1 : totalPages);
    }

    /**
     * 手動作成（service_type=MANUAL固定）。
     */
    public int createManual(String ipAddress, String targetAgent, String reason, LocalDateTime endAt, String username) throws SQLException {
        byte[] ipBytes = toIpBytes(ipAddress);
        if (ipBytes == null) {
            throw new SQLException("invalid ip address");
        }
        String sql = "INSERT INTO block_ip (ip_address, service_type, target_agent_name, reason, start_at, end_at, status, created_by, updated_by) " +
                "VALUES (?, 'MANUAL', ?, ?, NOW(), ?, 'ACTIVE', ?, ?)";
        try (Connection conn = DbService.getConnection()) {
            if (conn == null) { throw new SQLException("DB connection is null"); }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setBytes(1, ipBytes);
                ps.setString(2, targetAgent != null && !targetAgent.isBlank() ? targetAgent : null);
                ps.setString(3, reason);
                if (endAt != null) {
                    ps.setTimestamp(4, Timestamp.valueOf(endAt));
                } else {
                    ps.setNull(4, java.sql.Types.TIMESTAMP);
                }
                ps.setString(5, username);
                ps.setString(6, username);
                int inserted = ps.executeUpdate();
                if (inserted > 0) {
                    scheduleCleanupReschedule();
                }
                return inserted;
            }
        }
    }

    /**
     * ステータスをREVOKED(無効)に更新する。
     * @param id 対象ID
     * @param username 操作者
     * @return 更新件数
     * @throws SQLException 更新エラー時
     */
    public int revoke(long id, String username) throws SQLException {
        String sql = "UPDATE block_ip SET status = 'REVOKED', end_at = COALESCE(end_at, NOW()), updated_at = NOW(), updated_by = ? " +
                "WHERE id = ? AND status <> 'REVOKED'";
        try (Connection conn = DbService.getConnection()) {
            if (conn == null) { throw new SQLException("DB connection is null"); }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username);
                ps.setLong(2, id);
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    scheduleCleanupReschedule();
                }
                return updated;
            }
        }
    }

    /**
     * ステータスをACTIVEに更新する。
     * @param id 対象ID
     * @param username 操作者
     * @return 更新件数
     * @throws SQLException 更新エラー時
     */
    public int activate(long id, String username, boolean overrideEndAt, LocalDateTime newEndAt) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE block_ip SET status = 'ACTIVE', updated_at = NOW(), updated_by = ?");
        if (overrideEndAt) {
            if (newEndAt != null) {
                sql.append(", end_at = ?");
            } else {
                sql.append(", end_at = NULL");
            }
        }
        sql.append(" WHERE id = ? AND status <> 'ACTIVE'");

        try (Connection conn = DbService.getConnection()) {
            if (conn == null) { throw new SQLException("DB connection is null"); }
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                ps.setString(idx++, username);
                if (overrideEndAt && newEndAt != null) {
                    ps.setTimestamp(idx++, Timestamp.valueOf(newEndAt));
                }
                ps.setLong(idx, id);
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    scheduleCleanupReschedule();
                }
                return updated;
            }
        }
    }

    /**
     * エージェント名・終了時刻・理由を更新する。
     * @param id 対象ID
     * @param targetAgent 対象エージェント
     * @param reason 理由（必須）
     * @param endAt 終了時刻（null可）
     * @param username 操作者
     * @return 更新件数
     * @throws SQLException 更新エラー時
     */
    public int update(long id, String targetAgent, String reason, LocalDateTime endAt, String username) throws SQLException {
        String sql = "UPDATE block_ip SET target_agent_name = ?, end_at = ?, reason = ?, updated_at = NOW(), updated_by = ? WHERE id = ?";
        try (Connection conn = DbService.getConnection()) {
            if (conn == null) { throw new SQLException("DB connection is null"); }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, (targetAgent != null && !targetAgent.isBlank()) ? targetAgent : null);
                if (endAt != null) {
                    ps.setTimestamp(2, Timestamp.valueOf(endAt));
                } else {
                    ps.setNull(2, java.sql.Types.TIMESTAMP);
                }
                ps.setString(3, reason);
                ps.setString(4, username);
                ps.setLong(5, id);
                int updated = ps.executeUpdate();
                if (updated > 0) {
                    scheduleCleanupReschedule();
                }
                return updated;
            }
        }
    }

    /**
     * 削除（物理削除）。
     */
    public int delete(long id) throws SQLException {
        try (Connection conn = DbService.getConnection()) {
            if (conn == null) { throw new SQLException("DB connection is null"); }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM block_ip WHERE id = ?")) {
                ps.setLong(1, id);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    scheduleCleanupReschedule();
                }
                return deleted;
            }
        }
    }

    /**
     * 対象エージェント候補を取得する（重複排除、cloudflareを補完）。
     * @return エージェント名候補のリスト
     * @throws SQLException 取得失敗時
     */
    public List<Map<String, String>> listAgentNames() throws SQLException {
        try {
            return fetchAgentNames();
        } catch (NullPointerException npe) {
            // 初回アクセスでドライバのセッションNULLになるケースに備え、1回だけ再取得する
            AppLogger.warn("エージェント候補取得をリトライします: " + npe.getMessage());
            return fetchAgentNames();
        }
    }

    private List<Map<String, String>> fetchAgentNames() throws SQLException {
        String sql = "SELECT DISTINCT agent_name FROM agent_servers WHERE agent_name IS NOT NULL AND agent_name <> '' ORDER BY agent_name";
        List<String> raw = new ArrayList<>();
        Connection conn = DbService.getConnection();
        if (conn == null) {
            throw new SQLException("DB connection is null");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                raw.add(rs.getString(1));
            }
        }
        if (!raw.contains("cloudflare")) {
            raw.add("cloudflare");
        }
        List<Map<String, String>> list = new ArrayList<>();
        for (String name : raw) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String type = "cloudflare".equalsIgnoreCase(name) ? "CDN" : "AGENT";
            String label = ("CDN".equals(type) ? "CDN: " : "agent: ") + name;
            Map<String, String> entry = new HashMap<>();
            entry.put("value", name);
            entry.put("label", label);
            entry.put("type", type);
            list.add(entry);
        }
        return list;
    }

    private void scheduleCleanupReschedule() {
        try {
            DbService.rescheduleBlockIpCleanup();
        } catch (Exception ignored) {
            // スケジュール更新失敗は致命的でないため無視
        }
    }


    private long countTotal(Connection conn, String status, String serviceType, String q) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM block_ip WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            sql.append(" AND status = ?");
            params.add(status.toUpperCase());
        }
        if (serviceType != null && !serviceType.isBlank() && !"all".equalsIgnoreCase(serviceType)) {
            sql.append(" AND service_type = ?");
            params.add(serviceType.toUpperCase());
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim() + "%";
            sql.append(" AND (");
            sql.append("reason LIKE ? OR created_by LIKE ? OR updated_by LIKE ? OR INET6_NTOA(ip_address) LIKE ?");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
            byte[] exactIp = toIpBytes(q.trim());
            if (exactIp != null) {
                sql.append(" OR ip_address = ?");
                params.add(exactIp);
            }
            sql.append(")");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (Object p : params) ps.setObject(idx++, p);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return 0;
    }

    private String normalizeSort(String sort) {
        if (sort == null || sort.isBlank()) return "ip_address";
        return switch (sort) {
            case "ipAddress", "ip_address" -> "ip_address";
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            case "startAt" -> "start_at";
            case "endAt" -> "end_at";
            case "serviceType" -> "service_type";
            default -> sort.toLowerCase();
        };
    }

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static byte[] toIpBytes(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (Exception e) {
            AppLogger.warn("IP変換失敗: " + ip + " / " + e.getMessage());
            return null;
        }
    }

    private static String toIpString(byte[] ip) {
        if (ip == null) return "";
        try {
            return InetAddress.getByAddress(ip).getHostAddress();
        } catch (Exception e) {
            return "";
        }
    }

    /** 検索結果コンテナ */
    public record SearchResult(List<Map<String, Object>> items, long total, int page, int size, long totalPages) {}
}

