package com.edamame.security.modsecurity;

import com.edamame.security.tools.AppLogger;
import com.edamame.security.db.DbService;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ModSecurityハンドラークラス
 * ModSecurityのログ検出・アラート解析・保存機能を提供
 *
 * @author Edamame Team
 * @version 1.0.1
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
     * 定期的ModSecurityアラート照合タスクを開始
     * @param executor スケジューラ実行用のExecutorService
     * @param modSecurityQueue ModSecurityキュー
     * @param dbService データベースサービス
     */
    public static void startPeriodicAlertMatching(ScheduledExecutorService executor,
                                                 ModSecurityQueue modSecurityQueue,
                                                 DbService dbService) {
        try {
            AppLogger.log("ModSecurityアラート定期照合タスクを開始します", "INFO");

            // 定期的なアラート一致チェックタスク（5秒間隔）
            executor.scheduleAtFixedRate(() -> {
                try {
                    performPeriodicAlertMatching(modSecurityQueue, dbService);
                } catch (Exception e) {
                    AppLogger.error("ModSecurityアラート定期チェックでエラー: " + e.getMessage());
                }
            }, 5, 5, TimeUnit.SECONDS);

            AppLogger.info("ModSecurityアラート定期照合タスク開始完了（5秒間隔）");

        } catch (Exception e) {
            AppLogger.error("ModSecurityアラート定期照合タスク開始エラー: " + e.getMessage());
            throw new RuntimeException("ModSecurityアラート定期照合タスク開始に失敗", e);
        }
    }

    /**
     * 定期的なModSecurityアラート一致チェック
     * キューに残っているアラートと最近のアクセスログを照合
     * @param modSecurityQueue ModSecurityキュー
     * @param dbService データベースサービス
     */
    private static void performPeriodicAlertMatching(ModSecurityQueue modSecurityQueue, DbService dbService) {
        try {
            Map<String, Integer> queueStatus = modSecurityQueue.getQueueStatus();
            boolean hasAlerts = queueStatus.values().stream().anyMatch(count -> count > 0);

            if (!hasAlerts) {
                return; // キューが空の場合はスキップ
            }

            AppLogger.debug("定期的ModSecurityアラート一致チェック開始 - キュー状況: " + queueStatus);

            // 最近5分以内のアクセスログを取得してアラートと照合
            List<Map<String, Object>> recentAccessLogs = dbService.selectRecentAccessLogsForModSecMatching(5);

            int matchedCount = 0;
            for (Map<String, Object> accessLog : recentAccessLogs) {
                Long accessLogId = (Long) accessLog.get("id");
                String serverName = (String) accessLog.get("server_name");
                String method = (String) accessLog.get("method");
                String fullUrl = (String) accessLog.get("full_url");
                LocalDateTime accessTime = (LocalDateTime) accessLog.get("access_time");
                Boolean blockedByModSec = (Boolean) accessLog.get("blocked_by_modsec");

                // すでにModSecurityブロック済みの場合はスキップ
                if (Boolean.TRUE.equals(blockedByModSec)) {
                    continue;
                }

                // ModSecurityアラートキューから一致するアラートを検索
                List<ModSecurityQueue.ModSecurityAlert> matchingAlerts =
                    modSecurityQueue.findMatchingAlerts(serverName, method, fullUrl, accessTime);

                if (!matchingAlerts.isEmpty()) {
                    AppLogger.info("定期チェックでModSecurityアラート一致検出: " + matchingAlerts.size() +
                                 "件, access_log ID=" + accessLogId + ", URL=" + fullUrl);

                    // access_logのblocked_by_modsecをtrueに更新
                    dbService.updateAccessLogModSecStatus(accessLogId, true);

                    // 一致したアラートをmodsec_alertsテーブルに保存
                    for (ModSecurityQueue.ModSecurityAlert alert : matchingAlerts) {
                        saveModSecurityAlertToDatabase(accessLogId, alert, dbService);
                        AppLogger.info("定期チェック - ModSecurityアラート保存: access_log ID=" + accessLogId +
                                     ", ルール=" + alert.ruleId() + ", メッセージ=" + alert.message());
                    }
                    matchedCount++;
                }
            }

            if (matchedCount > 0) {
                AppLogger.info("定期チェックで " + matchedCount + " 件のModSecurityアラート一致を処理しました");
            } else {
                AppLogger.debug("定期チェック完了 - 新規一致なし");
            }

        } catch (Exception e) {
            AppLogger.error("定期的ModSecurityアラート一致チェックでエラー: " + e.getMessage());
        }
    }

    /**
     * ModSecurityのログ行かどうかを判定
     */
    public static boolean isModSecurityRawLog(String logLine) {
        return logLine != null && logLine.contains("ModSecurity:");
    }

    /**
     * ModSecurityログから情報を抽出（AgentTcpServer用）
     * @param rawLog ModSecurityのログ行
     * @return 抽出された情報のマップ
     */
    public static Map<String, String> extractModSecInfo(String rawLog) {
        Map<String, String> extractedInfo = new HashMap<>();
        try {
            String extractedRuleId = null;
            String extractedMsg = null;
            String extractedData = null;
            String extractedSeverity = null;
            String extractedUrl = null; // URL情報を追加

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

            // URL情報を複数のパターンで抽出（優先順位順）
            extractedUrl = extractUrlFromModSecLog(rawLog);
            if (extractedUrl != null && !extractedUrl.trim().isEmpty()) {
                extractedInfo.put("url", extractedUrl);
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

            if (extractedUrl == null) {
                extractedInfo.put("url", "");
            }

            // デバッグ用：抽出結果をログ出力
            AppLogger.debug("ModSecurity抽出結果 - ID: " + extractedInfo.get("id") +
                          ", MSG: " + extractedInfo.get("msg") +
                          ", DATA: " + extractedInfo.get("data") +
                          ", SEVERITY: " + extractedInfo.get("severity") +
                          ", URL: " + extractedInfo.get("url"));

        } catch (Exception e) {
            AppLogger.warn("ModSecurity情報抽出エラー: " + e.getMessage());
            // エラー時もデフォルト値を返す
            extractedInfo.put("id", "parse_error");
            extractedInfo.put("msg", "ModSecurity Parse Error");
            extractedInfo.put("data", "");
            extractedInfo.put("severity", "error");
            extractedInfo.put("url", "");
        }
        return extractedInfo;
    }

    /**
     * ModSecurityログからURL情報を抽出（改善版・優先順位付き）
     * @param rawLog ModSecurityログ
     * @return 抽出されたURL（クエリパラメータ含む）
     */
    private static String extractUrlFromModSecLog(String rawLog) {
        // URL抽出パターン（優先順位順）
        String[] urlPatterns = {
            // 1. request: "GET /path?query HTTP/1.1" - 最も完全なURL情報
            "request:\\s*\"(?:GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s+([^\\s\"]+)[^\"]*\"",

            // 2. request_uri（最も完全なURL情報）
            "\\[request_uri \"([^\"]+)\"\\]",

            // 3. data内のHTTPリクエスト行（GET /path?query HTTP/1.1）
            "\\[data \"[^\"]*?(?:GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s+([^\\s\"]+)[^\"]*?\"\\]",

            // 4. uri（基本的なパス情報）
            "\\[uri \"([^\"]+)\"\\]",

            // 5. file（ファイルパス情報）
            "\\[file \"([^\"]+)\"\\]",

            // 6. 生のHTTPリクエスト行
            "(?:GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s+([^\\s]+)\\s+HTTP",

            // 7. matched_var_name内のARGS情報からURL推測
            "\\[matched_var_name \"ARGS:([^:\"]+)\"\\]",

            // 8. その他のパターン
            "to\\s+([^\\s]+)\\s+(?:at|\\[)"
        };

        for (int i = 0; i < urlPatterns.length; i++) {
            String pattern = urlPatterns[i];
            try {
                Pattern regexPattern = Pattern.compile(pattern);
                Matcher matcher = regexPattern.matcher(rawLog);

                if (matcher.find()) {
                    String extractedUrl = matcher.group(1);

                    if (extractedUrl != null && !extractedUrl.trim().isEmpty()) {
                        // URL正規化
                        extractedUrl = normalizeModSecUrl(extractedUrl);

                        if (!extractedUrl.isEmpty() && isValidUrl(extractedUrl)) {
                            AppLogger.debug("ModSecurityログからURL抽出成功: " + extractedUrl +
                                          " (パターン優先度: " + (i + 1) + ", パターン: " + pattern + ")");
                            return extractedUrl;
                        }
                    }
                }
            } catch (Exception e) {
                AppLogger.debug("URL抽出パターン " + (i + 1) + " でエラー: " + e.getMessage());
            }
        }

        AppLogger.debug("ModSecurityログからURL抽出失敗、全パターンでマッチしませんでした: " +
                      (rawLog.length() > 200 ? rawLog.substring(0, 200) + "..." : rawLog));
        return "";
    }

    /**
     * ModSecurityから抽出されたURLを正規化
     * @param url 抽出されたURL
     * @return 正規化されたURL
     */
    private static String normalizeModSecUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }

        url = url.trim();

        // 明らかにURLでない場合は除外
        if (url.contains("ARGS:") || url.contains("REQUEST_") ||
            url.contains("HEADERS:") || url.contains("MATCHED_")) {
            return "";
        }

        // URLデコードが必要な場合は実行
        try {
            if (url.contains("%")) {
                url = java.net.URLDecoder.decode(url, "UTF-8");
            }
        } catch (Exception e) {
            AppLogger.debug("URLデコードエラー: " + e.getMessage());
            // デコードエラーでも元のURLを返す
        }

        // スラッシュで始まらない場合は追加（ただし完全なURLの場合は除く）
        if (!url.startsWith("/") && !url.startsWith("http")) {
            url = "/" + url;
        }

        return url;
    }

    /**
     * 抽出されたURLが有効かどうかを判定
     * @param url 判定対象URL
     * @return 有効な場合true
     */
    private static boolean isValidUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        // 基本的なURL形式チェック
        return url.startsWith("/") || url.startsWith("http") ||
               url.matches("^[a-zA-Z0-9][a-zA-Z0-9\\-._]*[a-zA-Z0-9]/.*");
    }

    /**
     * ModSecurityエラーログを解析してキューに追加
     * @param rawLog ModSecurityのログ行
     * @param serverName サーバー名
     * @param modSecurityQueue ModSecurityキュー
     */
    public static void processModSecurityAlertToQueue(String rawLog, String serverName, ModSecurityQueue modSecurityQueue) {
        try {
            // ModSecurityログから情報を抽出
            Map<String, String> extractedInfo = extractModSecInfo(rawLog);

            if (!extractedInfo.isEmpty()) {
                // キューに追加（時刻情報を詳細ログ出力）
                LocalDateTime alertTime = LocalDateTime.now();
                modSecurityQueue.addAlert(serverName, extractedInfo, rawLog);
                AppLogger.info("ModSecurityアラートをキューに追加: サーバー=" + serverName +
                              ", ルール=" + extractedInfo.get("id") +
                              ", URL=" + extractedInfo.get("url") +
                              ", 検出時刻=" + alertTime +
                              ", メッセージ=" + extractedInfo.get("msg"));
            } else {
                AppLogger.warn("ModSecurityログの解析に失敗: " + rawLog);
            }
        } catch (Exception e) {
            AppLogger.error("ModSecurityアラートキュー追加エラー: " + e.getMessage());
        }
    }

    /**
     * ModSecurityアラートをデータベースに保存
     * @param accessLogId アクセスログID
     * @param alert ModSecurityアラート
     * @param dbService データベースサービス
     */
    public static void saveModSecurityAlertToDatabase(Long accessLogId, ModSecurityQueue.ModSecurityAlert alert, DbService dbService) {
        try {
            Map<String, Object> alertData = new HashMap<>();

            // ModSecurityアラートの詳細情報を正しくマッピング
            String ruleId = alert.ruleId();
            String message = alert.message();
            String dataValue = alert.dataValue();
            String severity = alert.severity();

            // デフォルト値の処理を改善
            alertData.put("rule_id", ruleId != null && !ruleId.isEmpty() ? ruleId : "unknown");
            alertData.put("message", message != null && !message.isEmpty() ? message : "ModSecurity Alert");
            alertData.put("data_value", dataValue != null ? dataValue : "");

            // 重要度の数値変換処理を追加
            Integer severityValue = convertSeverityToInt(severity);
            alertData.put("severity", severityValue);

            alertData.put("server_name", alert.serverName());
            alertData.put("raw_log", alert.rawLog());
            alertData.put("detected_at", alert.detectedAt().toString());

            dbService.insertModSecAlert(accessLogId, alertData);

            AppLogger.info("ModSecurityアラートDB保存成功: access_log ID=" + accessLogId +
                         ", ルール=" + ruleId + ", メッセージ=" + message +
                         ", 重要度=" + severityValue);

        } catch (Exception e) {
            AppLogger.error("ModSecurityアラートDB保存エラー: " + e.getMessage());
            AppLogger.debug("失敗したアラート詳細: " + alert.toString());
        }
    }

    /**
     * ModSecurity重要度文字列を数値に変換
     * @param severity 重要度文字列
     * @return 重要度数値
     */
    public static Integer convertSeverityToInt(String severity) {
        if (severity == null || severity.isEmpty()) {
            return 0; // デフォルト値
        }

        try {
            // 数値文字列の場合は直接変換
            return Integer.parseInt(severity);
        } catch (NumberFormatException e) {
            // 文字列の場合はマッピング
            return switch (severity.toLowerCase()) {
                case "emergency" -> 0;
                case "alert" -> 1;
                case "critical" -> 2;
                case "error" -> 3;
                case "warning" -> 4;
                case "notice" -> 5;
                case "info" -> 6;
                case "debug" -> 7;
                default -> 2; // デフォルトは「critical」レベル
            };
        }
    }
}
