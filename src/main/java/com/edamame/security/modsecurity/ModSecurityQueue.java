package com.edamame.security.modsecurity;

import com.edamame.security.tools.AppLogger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ModSecurityアラート管理キュー
 * ModSecurityアラートを一時的に保存し、時間・URL一致による関連付けを行う
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class ModSecurityQueue {

    /**
     * ModSecurityアラート情報を保持するレコード
     */
    public static record ModSecurityAlert(
        String ruleId,
        String message,
        String dataValue,
        String severity,
        String serverName,
        String rawLog,
        LocalDateTime detectedAt,
        String extractedUrl  // アラートから抽出したURL情報
    ) {
        // Jackson等のJSONシリアライズ用。IDEで未使用警告が出ても削除禁止
    }

    // サーバー名単位でアラートキューを管理
    private final Map<String, Queue<ModSecurityAlert>> alertQueues = new HashMap<>();

    // アラートの保持期間（秒）- 60秒に延長して関連付け成功率を向上
    private static final int ALERT_RETENTION_SECONDS = 60;

    /**
     * ModSecurityクリーンアップタスクを開始
     * @param executor スケジューラ実行用のExecutorService
     */
    public void startCleanupTask(ScheduledExecutorService executor) {
        try {
            AppLogger.log("ModSecurityキューのクリーンアップタスクを開始します", "INFO");

            // クリーンアップタスク（30秒間隔）
            executor.scheduleAtFixedRate(() -> {
                try {
                    boolean executed = cleanupExpiredAlerts();
                    if (executed) {
                        AppLogger.debug("ModSecurityキューのクリーンアップ実行完了");
                    } else {
                        //AppLogger.debug("ModSecurityキューは空のためクリーンアップをスキップしました");
                    }
                } catch (Exception e) {
                    AppLogger.error("ModSecurityキューのクリーンアップでエラー: " + e.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);

            AppLogger.info("ModSecurityクリーンアップタスク開始完了（30秒間隔）");

        } catch (Exception e) {
            AppLogger.error("ModSecurityクリーンアップタスク開始エラー: " + e.getMessage());
            throw new RuntimeException("ModSecurityクリーンアップタスク開始に失敗", e);
        }
    }

    /**
     * ModSecurityアラートをキューに追加
     */
    public synchronized void addAlert(String serverName, Map<String, String> extractedInfo, String rawLog) {
        try {
            Queue<ModSecurityAlert> queue = alertQueues.computeIfAbsent(serverName, k -> new ConcurrentLinkedQueue<>());

            // ModSecHandlerから抽出されたURL情報を優先的に使用
            String extractedUrl = extractedInfo.getOrDefault("url", "");

            // ModSecHandlerでURL抽出できなかった場合のみフォールバック
            if (extractedUrl.isEmpty()) {
                extractedUrl = extractUrlFromRawLog(rawLog);
            }

            ModSecurityAlert alert = new ModSecurityAlert(
                extractedInfo.getOrDefault("id", "unknown"),
                extractedInfo.getOrDefault("msg", "ModSecurity Alert"),
                extractedInfo.getOrDefault("data", ""),
                extractedInfo.getOrDefault("severity", "unknown"),
                serverName,
                rawLog,
                LocalDateTime.now(),
                extractedUrl  // ModSecHandlerからの抽出結果を優先使用
            );

            queue.offer(alert);
            AppLogger.debug("ModSecurityアラートをキューに追加: サーバー=" + serverName +
                          ", ルール=" + alert.ruleId() + ", URL=" + alert.extractedUrl());

        } catch (Exception e) {
            AppLogger.error("ModSecurityアラートキュー追加エラー: " + e.getMessage());
        }
    }

    /**
     * 指定されたHTTPリクエストに一致するModSecurityアラートを検索・取得
     */
    public synchronized List<ModSecurityAlert> findMatchingAlerts(String serverName, String method,
                                                                String fullUrl, LocalDateTime accessTime) {
        List<ModSecurityAlert> matchingAlerts = new ArrayList<>();

        try {
            Queue<ModSecurityAlert> queue = alertQueues.get(serverName);
            if (queue == null || queue.isEmpty()) {
                AppLogger.debug("ModSecurityアラート検索: サーバー" + serverName + "のキューが空または存在しない");
                return matchingAlerts;
            }

            AppLogger.debug("ModSecurityアラート検索開始: サーバー=" + serverName +
                          ", URL=" + fullUrl + ", アクセス時刻=" + accessTime +
                          ", キュー内アラート数=" + queue.size());

            List<ModSecurityAlert> alertsToRemove = new ArrayList<>();

            for (ModSecurityAlert alert : queue) {
                // 時間範囲チェック（前後60秒以内に拡張）
                long secondsDiff = Math.abs(java.time.Duration.between(alert.detectedAt(), accessTime).getSeconds());

                AppLogger.debug("アラート時刻比較: アラート=" + alert.detectedAt() +
                              ", リクエスト=" + accessTime + ", 時間差=" + secondsDiff + "秒");

                if (secondsDiff > ALERT_RETENTION_SECONDS) {
                    AppLogger.debug("時間差が保持期間を超過: " + secondsDiff + "秒 > " + ALERT_RETENTION_SECONDS + "秒");
                    continue;
                }

                // URL一致チェック（詳細ログ付き）
                if (isUrlMatching(alert.extractedUrl(), fullUrl, method)) {
                    matchingAlerts.add(alert);
                    alertsToRemove.add(alert);
                    AppLogger.info("ModSecurityアラート一致成功: ルール=" + alert.ruleId() +
                                  ", アラートURL=" + alert.extractedUrl() +
                                  ", リクエストURL=" + fullUrl +
                                  ", 時間差=" + secondsDiff + "秒");
                } else {
                    AppLogger.debug("URL不一致: アラートURL=" + alert.extractedUrl() +
                                  ", リクエストURL=" + fullUrl);
                }
            }

            // 使用済みアラートをキューから削除
            for (ModSecurityAlert alertToRemove : alertsToRemove) {
                queue.remove(alertToRemove);
                AppLogger.debug("使用済みアラートをキューから削除: " + alertToRemove.ruleId());
            }

            if (matchingAlerts.isEmpty()) {
                AppLogger.debug("ModSecurityアラート一致結果: なし (サーバー=" + serverName +
                              ", URL=" + fullUrl + ", キュー内=" + queue.size() + "件)");
            }

        } catch (Exception e) {
            AppLogger.error("ModSecurityアラート検索エラー: " + e.getMessage());
        }

        return matchingAlerts;
    }

    /**
     * 期限切れアラートをクリーンアップ
     *
     * @return true=クリーンアップ処理を実行した（キューが空でなく、チェックを実行した）
     *         false=キューが空のためクリーンアップをスキップした
     */
    public synchronized boolean cleanupExpiredAlerts() {
        try {
            // キューがまったく存在しない、または全サーバー合計で0件なら処理をスキップ
            if (alertQueues.isEmpty()) {
                return false;
            }

            int total = 0;
            for (Queue<ModSecurityAlert> q : alertQueues.values()) {
                if (q != null) {
                    total += q.size();
                }
            }

            if (total == 0) {
                return false;
            }

            LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds(ALERT_RETENTION_SECONDS);
            int removedCount = 0;

            for (Queue<ModSecurityAlert> queue : alertQueues.values()) {
                Iterator<ModSecurityAlert> iterator = queue.iterator();
                while (iterator.hasNext()) {
                    ModSecurityAlert alert = iterator.next();
                    if (alert.detectedAt().isBefore(cutoffTime)) {
                        iterator.remove();
                        removedCount++;
                    }
                }
            }

            if (removedCount > 0) {
                AppLogger.debug("期限切れModSecurityアラートを削除: " + removedCount + "件");
            }

            return true;

        } catch (Exception e) {
            AppLogger.error("ModSecurityアラートクリーンアップエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * RawLogからURL情報を抽出（改善版）
     */
    private String extractUrlFromRawLog(String rawLog) {
        try {
            // 複数のパターンでURL抽出を試行
            String[] patterns = {
                "\\[uri \"([^\"]+)\"\\]",           // [uri "/path"]
                "\\[file \"([^\"]+)\"\\]",          // [file "/path"]
                "\\[request_uri \"([^\"]+)\"\\]",   // [request_uri "/path"]
                "\\[matched_var_name \"([^\"]+)\"\\]", // [matched_var_name "ARGS:param"]
                "\\[data \"([^\"]*(?:GET|POST|PUT|DELETE)\\s+([^\\s\"]+)[^\"]*?)\"\\]", // [data "GET /path HTTP/1.1"]
                "(?:GET|POST|PUT|DELETE)\\s+([^\\s]+)\\s+HTTP", // "GET /path HTTP/1.1"パターン
                "to\\s+([^\\s]+)\\s+(?:at|\\[)", // "to /path at"パターン
                "request:\\s*\"[^\"]*?\\s+([^\\s\"]+)\\s+[^\"]*?\"" // request: "GET /path HTTP/1.1"
            };

            for (String pattern : patterns) {
                java.util.regex.Pattern regexPattern = java.util.regex.Pattern.compile(pattern);
                java.util.regex.Matcher matcher = regexPattern.matcher(rawLog);

                if (matcher.find()) {
                    String extractedUrl = null;

                    // パターンによって抽出するグループを変える
                    if (pattern.contains("data.*(?:GET|POST|PUT|DELETE)") && matcher.groupCount() >= 2) {
                        extractedUrl = matcher.group(2); // HTTP methodの後のURL部分
                    } else {
                        extractedUrl = matcher.group(1);
                    }

                    if (extractedUrl != null && !extractedUrl.trim().isEmpty()) {
                        // URLの正規化
                        extractedUrl = normalizeExtractedUrl(extractedUrl);
                        AppLogger.debug("ModSecurityログからURL抽出成功: " + extractedUrl + " (パターン: " + pattern + ")");
                        return extractedUrl;
                    }
                }
            }

            // 全パターンで失敗した場合のフォールバック
            AppLogger.debug("ModSecurityログからURL抽出失敗、全パターンでマッチしませんでした");
            return "";

        } catch (Exception e) {
            AppLogger.debug("URL抽出エラー: " + e.getMessage());
            return "";
        }
    }

    /**
     * 抽出されたURLを正規化
     */
    private String normalizeExtractedUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }

        url = url.trim();

        // 明らかにURLでない場合は除外
        if (url.contains("ARGS:") || url.contains("REQUEST_") || url.contains("=")) {
            return "";
        }

        // URLデコードが必要な場合は実行
        try {
            if (url.contains("%")) {
                url = java.net.URLDecoder.decode(url, "UTF-8");
            }
        } catch (Exception e) {
            AppLogger.debug("URLデコードエラー: " + e.getMessage());
        }

        // スラッシュで始まらない場合は追加
        if (!url.startsWith("/")) {
            url = "/" + url;
        }

        return url;
    }

    /**
     * URL一致判定
     */
    private boolean isUrlMatching(String alertUrl, String requestUrl, String method) {
        try {
            if (alertUrl == null || alertUrl.isEmpty()) {
                return false;
            }

            // 完全一致
            if (alertUrl.equals(requestUrl)) {
                return true;
            }

            // パス部分の一致（クエリパラメータ除去して比較）
            String alertPath = alertUrl.split("\\?")[0];
            String requestPath = requestUrl.split("\\?")[0];

            if (alertPath.equals(requestPath)) {
                return true;
            }

            // 部分一致（アラートURLがリクエストURLに含まれる場合）
            if (requestUrl.contains(alertUrl)) {
                return true;
            }

            return false;
        } catch (Exception e) {
            AppLogger.debug("URL一致判定エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * キューの現在の状態を取得（デバッグ用）
     */
    public synchronized Map<String, Integer> getQueueStatus() {
        Map<String, Integer> status = new HashMap<>();
        for (Map.Entry<String, Queue<ModSecurityAlert>> entry : alertQueues.entrySet()) {
            status.put(entry.getKey(), entry.getValue().size());
        }
        return status;
    }
}
