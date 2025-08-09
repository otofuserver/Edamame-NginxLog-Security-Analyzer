package com.edamame.security.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * データベースの検索処理用クラス
 * サーバー・エージェント等のSELECT系処理を集約
 */
public class DbSelect {
    /**
     * サーバー名でサーバー情報を取得
     * @param conn データベース接続
     * @param serverName サーバー名
     * @return サーバー情報（id, server_description, log_path）をOptionalで返す
     * @throws SQLException SQL例外
     */
    public static Optional<ServerInfo> selectServerInfoByName(Connection conn, String serverName) throws SQLException {
        String sql = "SELECT id, server_description, log_path FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverName);
            try (ResultSet rs = stmt.executeQuery()) {
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
    }

    /**
     * サーバー名で存在有無を取得
     * @param conn データベース接続
     * @param serverName サーバー名
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsServerByName(Connection conn, String serverName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    /**
     * 指定registrationIdのpendingなブロック要求リストを取得
     * @param conn データベース接続
     * @param registrationId エージェント登録ID
     * @param limit 最大取得件数
     * @return ブロック要求リスト（Mapのリスト）
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectPendingBlockRequests(Connection conn, String registrationId, int limit) throws SQLException {
        String sql = """
            SELECT request_id, ip_address, duration, reason, chain_name
            FROM agent_block_requests
            WHERE registration_id = ? AND status = 'pending'
            ORDER BY created_at ASC
            LIMIT ?
            """;
        List<Map<String, Object>> blockRequests = new ArrayList<>();
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, registrationId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
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
    }

    /**
     * settingsテーブルからホワイトリスト設定（whitelist_mode, whitelist_ip）を取得
     * @param conn データベース接続
     * @return Map（whitelist_mode: Boolean, whitelist_ip: String）
     * @throws SQLException SQL例外
     */
    public static Map<String, Object> selectWhitelistSettings(Connection conn) throws SQLException {
        String sql = "SELECT whitelist_mode, whitelist_ip FROM settings WHERE id = 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Map<String, Object> result = new HashMap<>();
                result.put("whitelist_mode", rs.getBoolean("whitelist_mode"));
                result.put("whitelist_ip", rs.getString("whitelist_ip"));
                return result;
            }
        }
        return null;
    }

    /**
     * url_registryテーブルに指定のserver_name, method, full_urlが存在するか判定
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsUrlRegistryEntry(Connection conn, String serverName, String method, String fullUrl) throws SQLException {
        String sql = "SELECT COUNT(*) FROM url_registry WHERE server_name = ? AND method = ? AND full_url = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverName);
            stmt.setString(2, method);
            stmt.setString(3, fullUrl);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    /**
     * url_registryテーブルからis_whitelistedを取得
     * @param conn データベース接続
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return is_whitelisted値（存在しない��合はnull）
     * @throws SQLException SQL例外
     */
    public static Boolean selectIsWhitelistedFromUrlRegistry(Connection conn, String serverName, String method, String fullUrl) throws SQLException {
        String sql = "SELECT is_whitelisted FROM url_registry WHERE server_name = ? AND method = ? AND full_url = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serverName);
            stmt.setString(2, method);
            stmt.setString(3, fullUrl);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBoolean("is_whitelisted");
                }
            }
        }
        return null;
    }

    /**
     * サーバー情報DTO
     */
    public record ServerInfo(int id, String description, String logPath) {}
}
