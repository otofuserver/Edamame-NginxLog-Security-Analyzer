package com.edamame.security.db;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * データベースの検索処理用クラス
 * サーバー・エージェント等のSELECT系処理を集約
 * DbServiceとDbSessionを使用してConnection引��を完全排除
 * v2.0.0: Connection引数を完全廃止、DbService専用に統一
 */
public class DbSelect {

    /**
     * サーバー名でサーバー情報を取得
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @return サーバー情報（id, server_description, log_path）をOptionalで返す
     * @throws SQLException SQL例外
     */
    public static Optional<ServerInfo> selectServerInfoByName(DbService dbService, String serverName) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "SELECT id, server_description, log_path FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci";
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return Optional.of(new ServerInfo(
                                rs.getInt("id"),
                                rs.getString("server_description"),
                                rs.getString("log_path")
                            ));
                        }
                    }
                }
                return Optional.empty();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバー名で存在有無を取得
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsServerByName(DbService dbService, String serverName) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "SELECT COUNT(*) FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci";
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt(1) > 0;
                        }
                    }
                }
                return false;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 指定registrationIdのpendingなブロック要求リストを取得
     * @param dbService データベースサービス
     * @param registrationId エージェント登録ID
     * @param limit 最大取得件数
     * @return ブロック要求リスト（Mapのリスト）
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectPendingBlockRequests(DbService dbService, String registrationId, int limit) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT request_id, ip_address, duration, reason, chain_name
                    FROM agent_block_requests
                    WHERE registration_id = ? AND status = 'pending'
                    ORDER BY created_at ASC
                    LIMIT ?
                    """;
                List<Map<String, Object>> blockRequests = new ArrayList<>();
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, registrationId);
                    pstmt.setInt(2, limit);
                    try (var rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> request = new HashMap<>();
                            request.put("id", rs.getString("request_id"));
                            request.put("ipAddress", rs.getString("ip_address"));
                            request.put("duration", rs.getInt("duration"));
                            request.put("reason", rs.getString("reason"));
                            request.put("chain", rs.getString("chain_name"));
                            blockRequests.add(request);
                        }
                    }
                }
                return blockRequests;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * settingsテーブルからホワイトリスト設定（whitelist_mode, whitelist_ip）を取得
     * @param dbService データベースサービス
     * @return Map（whitelist_mode: Boolean, whitelist_ip: String）
     * @throws SQLException SQL例外
     */
    public static Map<String, Object> selectWhitelistSettings(DbService dbService) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "SELECT whitelist_mode, whitelist_ip FROM settings WHERE id = 1";
                try (var stmt = conn.prepareStatement(sql);
                     var rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("whitelist_mode", rs.getBoolean("whitelist_mode"));
                        result.put("whitelist_ip", rs.getString("whitelist_ip"));
                        return result;
                    }
                }
                return null;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * url_registryテーブルに指定のserver_name, method, full_urlが存在するか判定
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsUrlRegistryEntry(DbService dbService, String serverName, String method, String fullUrl) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "SELECT COUNT(*) FROM url_registry WHERE server_name = ? AND method = ? AND full_url = ?";
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    stmt.setString(2, method);
                    stmt.setString(3, fullUrl);
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getInt(1) > 0;
                        }
                    }
                }
                return false;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * url_registryテーブルからis_whitelistedを取得
     * @param dbService データベースサービス
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return is_whitelisted値（存在しない場合はnull）
     * @throws SQLException SQL例外
     */
    public static Boolean selectIsWhitelistedFromUrlRegistry(DbService dbService, String serverName, String method, String fullUrl) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = "SELECT is_whitelisted FROM url_registry WHERE server_name = ? AND method = ? AND full_url = ?";
                try (var stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    stmt.setString(2, method);
                    stmt.setString(3, fullUrl);
                    try (var rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            return rs.getBoolean("is_whitelisted");
                        }
                    }
                }
                return null;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 最近の指定分数以内のアクセスログを取得（ModSecurity照合用）
     * @param dbService データベースサービス
     * @param minutes 何分前までのログを取得するか
     * @return アクセスログのリスト
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectRecentAccessLogsForModSecMatching(DbService dbService, int minutes) throws SQLException {
        return dbService.getSession().executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT id, server_name, method, full_url, access_time, blocked_by_modsec
                    FROM access_log
                    WHERE access_time >= DATE_SUB(NOW(), INTERVAL ? MINUTE)
                      AND blocked_by_modsec = false
                    ORDER BY access_time DESC
                    LIMIT 1000
                    """;
                
                List<Map<String, Object>> results = new ArrayList<>();
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, minutes);
                    try (var rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> row = new HashMap<>();
                            row.put("id", rs.getLong("id"));
                            row.put("server_name", rs.getString("server_name"));
                            row.put("method", rs.getString("method"));
                            row.put("full_url", rs.getString("full_url"));
                            row.put("access_time", rs.getTimestamp("access_time").toLocalDateTime());
                            row.put("blocked_by_modsec", rs.getBoolean("blocked_by_modsec"));
                            results.add(row);
                        }
                    }
                }
                return results;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバー情報DTO
     */
    public record ServerInfo(int id, String description, String logPath) {}
}
