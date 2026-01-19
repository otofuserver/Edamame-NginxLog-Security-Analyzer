package com.edamame.security.suppression;

import com.edamame.security.db.DbService;
import com.edamame.security.tools.AppLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * URL抑止判定を行う管理クラス。
 * is_enabledな抑止ルールをサーバー名（またはall）で取得し、正規表現マッチしたらログを破棄する。
 */
public class UrlSuppressionManager {

    /**
     * 抑止対象ならtrueを返し、ヒットしたルールのアクセス情報を更新する。
     * @param serverName サーバー名
     * @param fullUrl フルURL
     * @return 抑止対象ならtrue
     */
    public static boolean shouldSuppress(String serverName, String fullUrl) {
        if (fullUrl == null || fullUrl.isBlank()) return false;
        String effectiveServer = (serverName == null || serverName.isBlank()) ? "all" : serverName;
        List<SuppressionRule> rules = loadActiveRules(effectiveServer);
        for (SuppressionRule rule : rules) {
            try {
                if (rule.pattern.matcher(fullUrl).find()) {
                    markHit(rule.id);
                    AppLogger.info("URL抑止ルールに一致: id=" + rule.id + ", server=" + rule.serverName + ", pattern=" + rule.rawPattern + ", url=" + fullUrl);
                    return true;
                }
            } catch (Exception e) {
                AppLogger.warn("URL抑止マッチングエラー: pattern=" + rule.rawPattern + " url=" + fullUrl + " msg=" + e.getMessage());
            }
        }
        return false;
    }

    private record SuppressionRule(long id, String serverName, String rawPattern, Pattern pattern) {}

    private static List<SuppressionRule> loadActiveRules(String serverName) {
        List<SuppressionRule> list = new ArrayList<>();
        String sql = "SELECT id, server_name, url_pattern FROM url_suppressions WHERE is_enabled = TRUE AND (server_name = 'all' OR server_name = ?)";
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, serverName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String srv = rs.getString("server_name");
                    String pat = rs.getString("url_pattern");
                    if (pat == null || pat.isBlank()) continue;
                    try {
                        Pattern compiled = Pattern.compile(pat, Pattern.CASE_INSENSITIVE);
                        list.add(new SuppressionRule(id, srv, pat, compiled));
                    } catch (Exception e) {
                        AppLogger.warn("URL抑止パターンコンパイル失敗: id=" + id + " pattern=" + pat + " msg=" + e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            AppLogger.error("URL抑止ルール取得エラー: " + e.getMessage());
        }
        return list;
    }

    private static void markHit(long id) {
        String sql = "UPDATE url_suppressions SET last_access_at = NOW(), drop_count = drop_count + 1 WHERE id = ?";
        try (Connection conn = DbService.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            AppLogger.warn("URL抑止ヒット更新エラー: id=" + id + " msg=" + e.getMessage());
        }
    }
}
