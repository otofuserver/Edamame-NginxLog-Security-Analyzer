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
    public record ModSecurityAlert(
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

    // アラートの保持期間（秒）: ModSecログとアクセスログのマッチングは +-30 秒以内を採用
    private static final int ALERT_RETENTION_SECONDS = 30;

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
    public synchronized List<ModSecurityAlert> findMatchingAlerts(String serverName,
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
                if (isUrlMatching(alert.extractedUrl(), fullUrl)) {
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
                url = java.net.URLDecoder.decode(url, java.nio.charset.StandardCharsets.UTF_8);
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
    private boolean isUrlMatching(String alertUrl, String requestUrl) {
        try {
            if (alertUrl == null || alertUrl.isEmpty()) {
                return false;
            }

            // 正規化（デコード・小文字化・末尾スラッシュ削除など）
            String normAlert = normalizeForComparison(alertUrl);
            String normRequest = normalizeForComparison(requestUrl);

            // 1) 完全一致（正規化済）
            if (normAlert.equals(normRequest)) return true;

            // 2) パス部分の一致（クエリパラメータを除去して比較）
            String alertPath = stripQuery(normAlert);
            String requestPath = stripQuery(normRequest);
            if (!alertPath.isEmpty() && alertPath.equals(requestPath)) return true;

            // 3) 部分一致（アラートURLがリクエストURLに含まれる、またはその逆）
            if (!normAlert.isEmpty() && normRequest.contains(normAlert)) return true;
            if (!normRequest.isEmpty() && normAlert.contains(normRequest)) return true;

            // 4) クエリパラメータ内の値が一致するケース（例: ?q=<script>...）
            String alertQuery = extractQuery(normAlert);
            String requestQuery = extractQuery(normRequest);
            if (!alertQuery.isEmpty() && !requestQuery.isEmpty()) {
                if (requestQuery.contains(alertQuery) || alertQuery.contains(requestQuery)) return true;
            }

            return false;
        } catch (Exception e) {
            AppLogger.debug("URL一致判定エラー: " + e.getMessage());
            return false;
        }
    }

    // URL比較用の正規化（デコード・小文字化・末尾スラッシュ削除）
    private String normalizeForComparison(String url) {
        if (url == null) return "";
        String s = url.trim();
        try {
            if (s.contains("%")) {
                s = java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            AppLogger.debug("URLデコードエラー(normalizeForComparison): " + e.getMessage());
        }
        // HTMLエンティティが含まれる場合は基本的な置換
        s = s.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&");
        // 小文字化して比較の緩和
        s = s.toLowerCase();
        // 末尾のスラッシュを除去（ルートは / のまま）
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // クエリ以降を削除してパスだけを返す
    private String stripQuery(String url) {
        if (url == null) return "";
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(0, idx) : url;
    }

    // クエリ文字列を取り出す（?以降）
    private String extractQuery(String url) {
        if (url == null) return "";
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(idx + 1) : "";
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

    /**
     * 指定サーバーのキューから rawLog に一致するアラートを削除（破棄）する
     * @param serverName サーバー名
     * @param rawLog ModSecurity の生ログテキスト（完全一致を試す）
     */
    public synchronized void removeAlertsForServerByRawLog(String serverName, String rawLog) {
        try {
            Queue<ModSecurityAlert> queue = alertQueues.get(serverName);
            if (queue == null || queue.isEmpty()) return;
            Iterator<ModSecurityAlert> it = queue.iterator();
            int removed = 0;
            while (it.hasNext()) {
                ModSecurityAlert a = it.next();
                if (a.rawLog() != null && a.rawLog().equals(rawLog)) {
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                AppLogger.debug("キューから該当ModSecurityアラートを削除(破棄): server=" + serverName + ", removed=" + removed);
            }
        } catch (Exception e) {
            AppLogger.error("キューからアラート削除エラー: " + e.getMessage());
        }
    }
}
