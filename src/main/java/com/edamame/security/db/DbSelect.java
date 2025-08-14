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
 * DbSessionを使用してConnection引数を完全排除
 * v2.1.0: DbService static化に対応、DbSessionを直接受け取る設計に変更
 */
public class DbSelect {

    /**
     * サーバー名でサーバー情報を取得
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @return サーバー情報（id, server_description, log_path）をOptionalで返す
     * @throws SQLException SQL例外
     */
    public static Optional<ServerInfo> selectServerInfoByName(DbSession dbSession, String serverName) throws SQLException {
        return dbSession.executeWithResult(conn -> {
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
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsServerByName(DbSession dbSession, String serverName) throws SQLException {
        return dbSession.executeWithResult(conn -> {
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
     * @param dbSession データベースセッション
     * @param registrationId エージェント登録ID
     * @param limit 最大取得件数
     * @return ブロック要求リスト（Mapのリスト）
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectPendingBlockRequests(DbSession dbSession, String registrationId, int limit) throws SQLException {
        return dbSession.executeWithResult(conn -> {
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
                            request.put("chainName", rs.getString("chain_name"));
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
     * settingsテーブルからホワイトリスト設定を取得
     * @param dbSession データベースセッション
     * @return ホワイトリスト設定Map
     * @throws SQLException SQL例外
     */
    public static Map<String, Object> selectWhitelistSettings(DbSession dbSession) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT whitelist_mode, whitelist_ip
                    FROM settings
                    WHERE id = 1
                    """;
                Map<String, Object> settings = new HashMap<>();
                try (var pstmt = conn.prepareStatement(sql)) {
                    try (var rs = pstmt.executeQuery()) {
                        if (rs.next()) {
                            settings.put("whitelist_mode", rs.getBoolean("whitelist_mode"));
                            settings.put("whitelist_ip", rs.getString("whitelist_ip"));
                        }
                    }
                }
                return settings;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * url_registryテーブルに指定のエントリが存在するか判定
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsUrlRegistryEntry(DbSession dbSession, String serverName, String method, String fullUrl) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT COUNT(*) FROM url_registry
                    WHERE server_name = ? AND method = ? AND full_url = ?
                    """;
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    pstmt.setString(2, method);
                    pstmt.setString(3, fullUrl);
                    try (var rs = pstmt.executeQuery()) {
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
     * @param dbSession データベースセッション
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return is_whitelisted値（存在しない場合はnull）
     * @throws SQLException SQL例外
     */
    public static Boolean selectIsWhitelistedFromUrlRegistry(DbSession dbSession, String serverName, String method, String fullUrl) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT is_whitelisted FROM url_registry
                    WHERE server_name = ? AND method = ? AND full_url = ?
                    """;
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, serverName);
                    pstmt.setString(2, method);
                    pstmt.setString(3, fullUrl);
                    try (var rs = pstmt.executeQuery()) {
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
     * @param dbSession データベースセッション
     * @param minutes 何分前までのログを取得するか
     * @return アクセスログのリスト（最大1000件）
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectRecentAccessLogsForModSecMatching(DbSession dbSession, int minutes) throws SQLException {
        return dbSession.executeWithResult(conn -> {
            try {
                String sql = """
                    SELECT id, server_name, method, full_url, access_time
                    FROM access_log
                    WHERE access_time >= NOW() - INTERVAL ? MINUTE
                    AND blocked_by_modsec = false
                    ORDER BY access_time DESC
                    LIMIT 1000
                    """;
                List<Map<String, Object>> accessLogs = new ArrayList<>();
                try (var pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, minutes);
                    try (var rs = pstmt.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> log = new HashMap<>();
                            log.put("id", rs.getLong("id"));
                            log.put("server_name", rs.getString("server_name"));
                            log.put("method", rs.getString("method"));
                            log.put("full_url", rs.getString("full_url"));
                            log.put("access_time", rs.getTimestamp("access_time").toLocalDateTime());
                            accessLogs.add(log);
                        }
                    }
                }
                return accessLogs;
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * サーバー情報レコードクラス
     */
    public record ServerInfo(int id, String description, String logPath) {}
}
