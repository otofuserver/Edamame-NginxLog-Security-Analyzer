package com.edamame.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ModSecurityハンドラークラス
 * ModSecurityのログ検出・アラート解析・保存機能を提供
 */
public class ModSecHandler {

    // ModSecurityのブロック行を検出するパターン
    private static final Pattern[] BLOCK_PATTERNS = {
        Pattern.compile("ModSecurity: Access denied", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ModSecurity.*blocked", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ModSecurity.*denied", Pattern.CASE_INSENSITIVE)
    };

    // アラート情報抽出用のパターン
    private static final Pattern RULE_ID_PATTERN = Pattern.compile("\\[id \"(\\d+)\"\\]");
    private static final Pattern MSG_PATTERN = Pattern.compile("\\[msg \"([^\"]+)\"\\]");
    private static final Pattern DATA_PATTERN = Pattern.compile("\\[data \"([^\"]+)\"\\]");
    private static final Pattern SEVERITY_PATTERN = Pattern.compile("\\[severity \"([^\"]+)\"\\]");

    /**
     * ログ行がModSecurityブロックかどうかを判定
     * @param line ログの1行
     * @return ブロック検出結果（true/false）
     */
    public static boolean detectModsecBlock(String line) {
        if (line == null || line.trim().isEmpty()) {
            return false;
        }

        for (Pattern pattern : BLOCK_PATTERNS) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * ModSecurityアラート行からアラート情報を抽出
     * @param line ModSecurityアラート行
     * @param logFunc ログ出力用関数（省略可）
     * @return アラート情報のリスト
     */
    public static List<Map<String, String>> parseModsecAlert(String line, BiConsumer<String, String> logFunc) {
        List<Map<String, String>> alerts = new ArrayList<>();

        try {
            // 各パターンでマッチする情報を抽出
            List<String> ruleIds = extractMatches(RULE_ID_PATTERN, line);
            List<String> messages = extractMatches(MSG_PATTERN, line);
            List<String> dataMatches = extractMatches(DATA_PATTERN, line);
            List<String> severityMatches = extractMatches(SEVERITY_PATTERN, line);

            // 抽出したデータを組み合わせてアラート情報を作成
            int maxCount = Math.max(Math.max(ruleIds.size(), messages.size()),
                                  Math.max(dataMatches.size(), severityMatches.size()));

            for (int i = 0; i < maxCount; i++) {
                Map<String, String> alert = new HashMap<>();
                alert.put("rule_id", i < ruleIds.size() ? ruleIds.get(i) : "");
                alert.put("msg", i < messages.size() ? messages.get(i) : "");
                alert.put("data", i < dataMatches.size() ? dataMatches.get(i) : "");
                alert.put("severity", i < severityMatches.size() ? severityMatches.get(i) : "");
                alerts.add(alert);
            }

            // アラート情報が抽出できない場合は、基本的な情報のみ保存
            if (alerts.isEmpty()) {
                Map<String, String> alert = new HashMap<>();
                alert.put("rule_id", "unknown");
                alert.put("msg", "ModSecurity Alert Detected");
                alert.put("data", line.length() > 500 ? line.substring(0, 500) : line);
                alert.put("severity", "unknown");
                alerts.add(alert);
            }

        } catch (Exception e) {
            // パースエラー時は基本的な情報のみ保存
            if (logFunc != null) {
                logFunc.accept("ModSecurityアラートの解析でエラー: " + e.getMessage(), "WARN");
            }

            Map<String, String> alert = new HashMap<>();
            alert.put("rule_id", "parse_error");
            alert.put("msg", "ModSecurity Alert Parse Error");
            alert.put("data", line.length() > 200 ? line.substring(0, 200) : line);
            alert.put("severity", "error");
            alerts.add(alert);
        }

        return alerts;
    }

    /**
     * 正規表現パターンでマッチする文字列のリストを抽出
     * @param pattern 正規表現パターン
     * @param text 検索対象テキスト
     * @return マッチした文字列のリスト
     */
    private static List<String> extractMatches(Pattern pattern, String text) {
        List<String> matches = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            matches.add(matcher.group(1));
        }

        return matches;
    }

    /**
     * ModSecurityアラート情報をデータベースに保存
     * @param conn データベース接続
     * @param logId 関連するアクセスログのID
     * @param alerts アラート情報のリスト
     * @param logFunc ログ出力用関数（省略可）
     * @return 保存に成功した場合true
     */
    public static boolean saveModsecAlerts(Connection conn, long logId, List<Map<String, String>> alerts, BiConsumer<String, String> logFunc) {
        if (conn == null || alerts == null || alerts.isEmpty()) {
            return false;
        }

        // 正しいカラム名に修正: created_at -> なし（デフォルト値を使用）
        String insertSql = "INSERT INTO modsec_alerts (access_log_id, rule_id, message, data_value, severity) " +
                          "VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

            for (Map<String, String> alert : alerts) {
                pstmt.setLong(1, logId);
                pstmt.setString(2, alert.get("rule_id"));
                pstmt.setString(3, alert.get("msg"));      // "msg"の値を"message"カラムに
                pstmt.setString(4, alert.get("data"));     // "data"の値を"data_value"カラムに
                pstmt.setString(5, alert.get("severity"));

                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();

            // すべての挿入が成功したかチェック
            for (int result : results) {
                if (result <= 0) {
                    if (logFunc != null) {
                        logFunc.accept("ModSecurityアラートの一部保存に失敗しました", "WARN");
                    }
                    return false;
                }
            }

            if (logFunc != null) {
                logFunc.accept(String.format("ModSecurityアラート %d件を保存しました (ログID: %d)",
                    alerts.size(), logId), "INFO");
            }

            return true;

        } catch (SQLException e) {
            if (logFunc != null) {
                logFunc.accept("ModSecurityアラート保存エラー: " + e.getMessage(), "ERROR");
            }
            return false;
        }
    }

    /**
     * 指定されたアクセスログIDに関連するModSecurityアラートの数を取得
     * @param conn データベース接続
     * @param logId アクセスログID
     * @return アラート数（エラー時は-1）
     */
    public static int getAlertCount(Connection conn, long logId) {
        if (conn == null) {
            return -1;
        }

        String selectSql = "SELECT COUNT(*) FROM modsec_alerts WHERE access_log_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
            pstmt.setLong(1, logId);

            var rs = pstmt.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            // エラーログは呼び出し側で出力
        }

        return -1;
    }

    /**
     * ModSecurityアラートテーブルの統計情報を取得
     * @param conn データベース接続
     * @return 統計情報のMap（総アラート数、ユニークルールID数など）
     */
    public static Map<String, Object> getAlertStatistics(Connection conn) {
        Map<String, Object> stats = new HashMap<>();

        if (conn == null) {
            return stats;
        }

        try {
            // 総アラート数
            String totalSql = "SELECT COUNT(*) as total_alerts FROM modsec_alerts";
            try (PreparedStatement pstmt = conn.prepareStatement(totalSql)) {
                var rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("total_alerts", rs.getInt("total_alerts"));
                }
            }

            // ユニークルールID数
            String uniqueRulesSql = "SELECT COUNT(DISTINCT rule_id) as unique_rules FROM modsec_alerts WHERE rule_id != 'unknown' AND rule_id != 'parse_error'";
            try (PreparedStatement pstmt = conn.prepareStatement(uniqueRulesSql)) {
                var rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("unique_rules", rs.getInt("unique_rules"));
                }
            }

            // 今日のアラート数
            String todaySql = "SELECT COUNT(*) as today_alerts FROM modsec_alerts WHERE DATE(detected_at) = CURDATE()";
            try (PreparedStatement pstmt = conn.prepareStatement(todaySql)) {
                var rs = pstmt.executeQuery();
                if (rs.next()) {
                    stats.put("today_alerts", rs.getInt("today_alerts"));
                }
            }

        } catch (SQLException e) {
            // 統計取得エラー時は空のMapを返す
        }

        return stats;
    }

    /**
     * ModSecurityアラートをサーバー名付きでデータベースに保存
     * @param conn データベース接続
     * @param accessLogId 関連するアクセスログID
     * @param alerts アラート情報のリスト
     * @param serverName サーバー名
     * @param logFunc ログ出力関数
     * @return 保存成功可否
     */
    public static boolean saveModsecAlertsWithServerName(Connection conn, long accessLogId, 
                                                       List<Map<String, String>> alerts, 
                                                       String serverName,
                                                       BiConsumer<String, String> logFunc) {
        if (alerts == null || alerts.isEmpty()) {
            return true; // アラートがない場合は成功とみなす
        }

        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        String sql = """
            INSERT INTO modsec_alerts (access_log_id, rule_id, severity, message, data_value, server_name, detected_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW())
            """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (Map<String, String> alert : alerts) {
                pstmt.setLong(1, accessLogId);
                pstmt.setString(2, alert.getOrDefault("rule_id", ""));
                pstmt.setString(3, alert.getOrDefault("severity", ""));
                pstmt.setString(4, alert.getOrDefault("message", ""));
                pstmt.setString(5, alert.getOrDefault("data_value", ""));
                pstmt.setString(6, serverName != null ? serverName : "default");
                pstmt.addBatch();
            }

            int[] results = pstmt.executeBatch();
            int successCount = 0;
            for (int result : results) {
                if (result > 0) successCount++;
            }

            if (successCount > 0) {
                log.accept(String.format("ModSecurityアラート保存完了: %d件 (サーバー: %s, アクセスログID: %d)",
                    successCount, serverName, accessLogId), "DEBUG");
                return true;
            } else {
                log.accept("ModSecurityアラートの保存で全て失敗しました", "WARN");
                return false;
            }

        } catch (SQLException e) {
            log.accept("ModSecurityアラート保存エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }
}