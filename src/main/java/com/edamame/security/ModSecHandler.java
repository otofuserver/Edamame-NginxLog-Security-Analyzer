package com.edamame.security;

import com.edamame.security.tools.AppLogger;

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
            AppLogger.warn("ModSecurityアラートの解析でエラー: " + e.getMessage());
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
                AppLogger.debug(String.format("ModSecurityアラート保存完了: %d件 (サーバー: %s, アクセスログID: %d)",
                    successCount, serverName, accessLogId));
                return true;
            } else {
                AppLogger.warn("ModSecurityアラートの保存で全て失敗しました");
                return false;
            }

        } catch (SQLException e) {
            AppLogger.error("ModSecurityアラート保存エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * ModSecurityログから情報を抽出（AgentTcpServer用）
     * @param rawLog ModSecurityのログ行
     * @param logFunction ログ出力関数
     * @return 抽出された情報のマップ
     */
    public static Map<String, String> extractModSecInfo(String rawLog, BiConsumer<String, String> logFunction) {
        Map<String, String> extractedInfo = new HashMap<>();
        try {
            String extractedRuleId = null;
            String extractedMsg = null;
            String extractedData = null;
            String extractedSeverity = null;

            // Rule IDを抽出
            Matcher ruleIdMatcher = RULE_ID_PATTERN.matcher(rawLog);
            if (ruleIdMatcher.find()) {
                extractedRuleId = ruleIdMatcher.group(1);
                extractedInfo.put("id", extractedRuleId);
            }
            
            // メッセージを抽出
            Matcher msgMatcher = MSG_PATTERN.matcher(rawLog);
            if (msgMatcher.find()) {
                extractedMsg = msgMatcher.group(1);
                extractedInfo.put("msg", extractedMsg);
            }
            
            // データを抽出
            Matcher dataMatcher = DATA_PATTERN.matcher(rawLog);
            if (dataMatcher.find()) {
                extractedData = dataMatcher.group(1);
                extractedInfo.put("data", extractedData);
            }
            
            // 重要度を抽出
            Matcher severityMatcher = SEVERITY_PATTERN.matcher(rawLog);
            if (severityMatcher.find()) {
                extractedSeverity = severityMatcher.group(1);
                extractedInfo.put("severity", extractedSeverity);
            }
            
            // 抽出されなかった項目にデフォルト値を設定
            if (extractedRuleId == null) {
                extractedInfo.put("id", "unknown");
            }

            if (extractedMsg == null) {
                // ルールIDがある場合はそれを含めたメッセージを生成
                if (extractedRuleId != null) {
                    extractedInfo.put("msg", "ModSecurity Rule " + extractedRuleId + " triggered");
                } else {
                    extractedInfo.put("msg", "ModSecurity Access Denied");
                }
            }

            if (extractedData == null) {
                extractedInfo.put("data", "");
            }

            if (extractedSeverity == null) {
                extractedInfo.put("severity", "unknown");
            }
            
            // デバッグ用：抽出結果をログ出力
            AppLogger.debug("ModSecurity抽出結果 - ID: " + extractedInfo.get("id") +
                          ", MSG: " + extractedInfo.get("msg") +
                          ", DATA: " + extractedInfo.get("data") +
                          ", SEVERITY: " + extractedInfo.get("severity"));

        } catch (Exception e) {
            AppLogger.warn("ModSecurity情報抽出エラー: " + e.getMessage());
            // エラー時もデフォルト値を返す
            extractedInfo.put("id", "parse_error");
            extractedInfo.put("msg", "ModSecurity Parse Error");
            extractedInfo.put("data", "");
            extractedInfo.put("severity", "error");
        }
        return extractedInfo;
    }
}