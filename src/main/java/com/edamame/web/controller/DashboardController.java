package com.edamame.web.controller;

import com.edamame.web.config.WebConfig;
import com.edamame.web.service.DataService;
import com.edamame.web.security.WebSecurityUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * ダッシュボードコントローラークラス
 * ダッシュボード画面の表示を担当（XSS対策強化版）
 */
public class DashboardController {

    private final DataService dataService;
    private final WebConfig webConfig;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param dataService データサービス
     * @param webConfig Web設定
     * @param logFunction ログ出力関数
     */
    public DashboardController(DataService dataService, WebConfig webConfig, BiConsumer<String, String> logFunction) {
        this.dataService = dataService;
        this.webConfig = webConfig;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
    }

    /**
     * ダッシュボードリクエストを処理（セキュリティ強化版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleDashboard(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // セッション情報を取得（認証フィルターで設定済み）
            String username = (String) exchange.getAttribute("username");
            if (username == null) {
                // 認証情報がない場合はログイン画面にリダイレクト
                exchange.getResponseHeaders().set("Location", "/login");
                exchange.sendResponseHeaders(302, -1);
                return;
            }

            // セキュリティヘッダーを設定
            applySecurityHeaders(exchange);

            // リクエストの検証
            if (!validateRequest(exchange)) {
                logFunction.accept("不正なリクエストを検知してブロックしました: " + exchange.getRequestURI(), "SECURITY");
                sendErrorResponse(exchange, 400, "Invalid Request");
                return;
            }

            // データベース接続確認
            if (!dataService.isConnectionValid()) {
                sendErrorResponse(exchange, 503, "データベース接続エラー");
                return;
            }

            // ダッシュボードデータを取得
            Map<String, Object> dashboardData = dataService.getDashboardStats();

            // 現在のユーザー情報をデータに追加
            dashboardData.put("currentUser", username);

            // HTMLを生成（XSS対策適用）
            String html = generateSecureDashboardHtml(dashboardData);

            // レスポンス送信
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html.getBytes(StandardCharsets.UTF_8));
            }

            logFunction.accept("ダッシュボード画面表示完了（セキュリティ強化版）", "DEBUG");

        } catch (Exception e) {
            logFunction.accept("ダッシュボード処理エラー: " + e.getMessage(), "ERROR");
            sendErrorResponse(exchange, 500, "内部サーバーエラー");
        }
    }

    /**
     * セキュリティヘッダーを適用
     * @param exchange HTTPエクスチェンジ
     */
    private void applySecurityHeaders(HttpExchange exchange) {
        Map<String, String> securityHeaders = WebSecurityUtils.getSecurityHeaders();
        for (Map.Entry<String, String> header : securityHeaders.entrySet()) {
            exchange.getResponseHeaders().set(header.getKey(), header.getValue());
        }
    }

    /**
     * リクエストを検証
     * @param exchange HTTPエクスチェンジ
     * @return 安全なリクエストの場合true
     */
    private boolean validateRequest(HttpExchange exchange) {
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String query = exchange.getRequestURI().getQuery();

        // User-Agentのチェック
        if (userAgent != null && WebSecurityUtils.detectXSS(userAgent)) {
            logFunction.accept("不正なUser-Agentを検知: " + userAgent, "SECURITY");
            return false;
        }

        // Refererのチェック
        if (referer != null && WebSecurityUtils.detectXSS(referer)) {
            logFunction.accept("不正なRefererを検知: " + referer, "SECURITY");
            return false;
        }

        // クエリパラメータのチェック
        if (query != null && (WebSecurityUtils.detectXSS(query) || WebSecurityUtils.detectSqlInjection(query))) {
            logFunction.accept("不正なクエリパラメータを検知: " + query, "SECURITY");
            return false;
        }

        return true;
    }

    /**
     * セキュアなダッシュボードHTMLを生成（XSS対策適用）
     * @param data ダッシュボードデータ
     * @return 生成されたHTML
     */
    private String generateSecureDashboardHtml(Map<String, Object> data) {
        // セッション情報を取得
        String username = (String) data.get("currentUser");
        if (username == null) {
            username = "Unknown";
        }

        String template = webConfig.getTemplate("dashboard");

        // 基本情報を置換（XSS対策適用）
        String html = template
            .replace("{{APP_TITLE}}", WebSecurityUtils.escapeHtml(webConfig.getAppTitle()))
            .replace("{{APP_DESCRIPTION}}", WebSecurityUtils.escapeHtml(webConfig.getAppDescription()))
            .replace("{{APP_VERSION}}", WebSecurityUtils.escapeHtml("v1.0.0"))
            .replace("{{CURRENT_TIME}}", WebSecurityUtils.escapeHtml(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
            .replace("{{SERVER_STATUS}}", WebSecurityUtils.escapeHtml(dataService.isConnectionValid() ? "稼働中" : "エラー"))
            .replace("{{CURRENT_USER}}", WebSecurityUtils.escapeHtml(username));

        // 統計情報を置換（XSS対策適用）
        html = html
            .replace("{{TOTAL_ACCESS}}", WebSecurityUtils.escapeHtml(formatNumber(data.get("totalAccess"))))
            .replace("{{TOTAL_ATTACKS}}", WebSecurityUtils.escapeHtml(formatNumber(data.get("totalAttacks"))))
            .replace("{{MODSEC_BLOCKS}}", WebSecurityUtils.escapeHtml(formatNumber(data.get("modsecBlocks"))))
            .replace("{{ACTIVE_SERVERS}}", WebSecurityUtils.escapeHtml(formatNumber(data.get("activeServers"))));

        // サーバーごとの統計表示を生成（XSS対策適用）
        html = html.replace("{{SERVER_STATS}}", generateSecureServerStatsHtml(data.get("serverStats")));

        // 最新アラート部分を生成（XSS対策適用）
        html = html.replace("{{RECENT_ALERTS}}", generateSecureAlertsHtml(data.get("recentAlerts")));

        // サーバー一覧部分を生成（XSS対策適用）
        html = html.replace("{{SERVER_LIST}}", generateSecureServersHtml(data.get("serverList")));

        // 攻撃タイプ統計部分を生成（XSS対策適用）
        html = html.replace("{{ATTACK_TYPES}}", generateSecureAttackTypesHtml(data.get("attackTypes")));

        // 自動更新設定
        if (webConfig.isEnableAutoRefresh()) {
            html = html
                .replace("{{#AUTO_REFRESH}}", "")
                .replace("{{/AUTO_REFRESH}}", "")
                .replace("{{REFRESH_INTERVAL}}", WebSecurityUtils.escapeHtml(String.valueOf(webConfig.getRefreshInterval())));
        } else {
            html = removeConditionalBlocks(html, "AUTO_REFRESH");
        }

        return html;
    }

    /**
     * セキュアなアラート一覧HTMLを生成（XSS対策適用）
     * @param alertsData アラートデータ
     * @return 生成されたHTML
     */
    @SuppressWarnings("unchecked")
    private String generateSecureAlertsHtml(Object alertsData) {
        if (!(alertsData instanceof List<?> alerts) || alerts.isEmpty()) {
            return "<div class='alert-item'>最近のアラートはありません</div>";
        }

        StringBuilder html = new StringBuilder();
        for (Object alertObj : alerts) {
            if (alertObj instanceof Map<?, ?> alert) {
                @SuppressWarnings("unchecked")
                Map<String, Object> alertMap = (Map<String, Object>) alert;

                String severityLevel = WebSecurityUtils.sanitizeInput((String) alertMap.getOrDefault("severityLevel", "low"));
                String serverName = WebSecurityUtils.sanitizeInput((String) alertMap.getOrDefault("serverName", "unknown"));
                String accessTime = WebSecurityUtils.sanitizeInput((String) alertMap.getOrDefault("accessTime", ""));
                String ipAddress = WebSecurityUtils.sanitizeInput((String) alertMap.getOrDefault("ipAddress", ""));
                String attackType = WebSecurityUtils.sanitizeInput((String) alertMap.getOrDefault("attackType", ""));
                // URL表示専用のサニタイズを使用（正当なURL文字を保持）
                String url = WebSecurityUtils.sanitizeUrlForDisplay((String) alertMap.getOrDefault("url", ""));

                // URLの長さ制限（XSS対策）
                String displayUrl = url.length() > 80 ? url.substring(0, 80) + "..." : url;

                html.append(String.format("""
                    <div class="alert-item %s">
                        <div class="alert-header">
                            <span class="alert-time">%s</span>
                            <span class="alert-server">%s</span>
                        </div>
                        <div class="alert-content">
                            <strong>%s</strong> からの攻撃: %s<br>
                            <small>URL: %s</small>
                        </div>
                    </div>
                    """, severityLevel, accessTime, serverName, ipAddress, attackType, displayUrl));
            }
        }

        return html.toString();
    }

    /**
     * セキュアなサーバー一覧HTMLを生成（XSS対策適用）
     * @param serversData サーバーデータ
     * @return 生成されたHTML
     */
    @SuppressWarnings("unchecked")
    private String generateSecureServersHtml(Object serversData) {
        if (!(serversData instanceof List<?> servers) || servers.isEmpty()) {
            return "<div class='server-item'>登録されたサーバーがありません</div>";
        }

        StringBuilder html = new StringBuilder();
        for (Object serverObj : servers) {
            if (serverObj instanceof Map<?, ?> server) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serverMap = (Map<String, Object>) server;

                String name = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("name", "unknown"));
                String description = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("description", ""));
                String status = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("status", "unknown"));
                String lastLogReceived = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("lastLogReceived", "未記録"));
                Object accessCount = serverMap.getOrDefault("todayAccessCount", 0);

                String statusClass = switch (status) {
                    case "online" -> "online";
                    case "offline", "stale" -> "offline";
                    default -> "offline";
                };

                String statusText = switch (status) {
                    case "online" -> "オンライン";
                    case "offline" -> "オフライン";
                    case "stale" -> "データ古い";
                    default -> "不明";
                };

                html.append(String.format("""
                    <div class="server-item">
                        <div class="server-info">
                            <strong>%s</strong><br>
                            <small>%s</small><br>
                            <small>最終ログ: %s | 今日のアクセス: %s件</small>
                        </div>
                        <span class="server-status %s">%s</span>
                    </div>
                    """, name, description.isEmpty() ? "説明なし" : description,
                    lastLogReceived, WebSecurityUtils.escapeHtml(String.valueOf(accessCount)), statusClass, statusText));
            }
        }

        return html.toString();
    }

    /**
     * セキュアな攻撃タイプ統計HTMLを生成（XSS対策適用）
     * @param attackTypesData 攻撃タイプデータ
     * @return 生成されたHTML
     */
    @SuppressWarnings("unchecked")
    private String generateSecureAttackTypesHtml(Object attackTypesData) {
        if (!(attackTypesData instanceof List<?> attackTypes) || attackTypes.isEmpty()) {
            return "<div class='attack-type-item'>今日の攻撃検知はありません</div>";
        }

        StringBuilder html = new StringBuilder();
        for (Object attackTypeObj : attackTypes) {
            if (attackTypeObj instanceof Map<?, ?> attackType) {
                @SuppressWarnings("unchecked")
                Map<String, Object> attackTypeMap = (Map<String, Object>) attackType;

                String type = WebSecurityUtils.sanitizeInput((String) attackTypeMap.getOrDefault("type", "unknown"));
                Object count = attackTypeMap.getOrDefault("count", 0);
                String description = WebSecurityUtils.sanitizeInput((String) attackTypeMap.getOrDefault("description", ""));

                html.append(String.format("""
                    <div class="attack-type-item">
                        <div class="attack-type-info">
                            <strong>%s</strong><br>
                            <small>%s</small>
                        </div>
                        <span class="attack-count">%s</span>
                    </div>
                    """, type, description, WebSecurityUtils.escapeHtml(String.valueOf(count))));
            }
        }

        return html.toString();
    }

    /**
     * サーバーごとの統計表示HTMLを生成（XSS対策適用）
     * @param serverStatsData サーバー統計データ
     * @return 生成されたHTML
     */
    @SuppressWarnings("unchecked")
    private String generateSecureServerStatsHtml(Object serverStatsData) {
        if (!(serverStatsData instanceof List<?> serverStats) || serverStats.isEmpty()) {
            return "<div class='server-stat-item'>今日のサーバー統計はありません</div>";
        }

        StringBuilder html = new StringBuilder();
        for (Object serverStatObj : serverStats) {
            if (serverStatObj instanceof Map<?, ?> serverStat) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serverStatMap = (Map<String, Object>) serverStat;

                String serverName = WebSecurityUtils.sanitizeInput((String) serverStatMap.getOrDefault("serverName", "unknown"));
                Object totalAccess = serverStatMap.getOrDefault("totalAccess", 0);
                Object attackCount = serverStatMap.getOrDefault("attackCount", 0);
                Object modsecBlocks = serverStatMap.getOrDefault("modsecBlocks", 0);

                html.append(String.format("""
                    <div class="server-stat-item">
                        <div class="server-stat-header">
                            <strong>%s</strong>
                        </div>
                        <div class="server-stat-content">
                            <div class="stat-value-item stat-access">
                                <span class="stat-number">%s</span>
                                <div class="stat-label">総アクセス</div>
                            </div>
                            <div class="stat-value-item stat-attack">
                                <span class="stat-number">%s</span>
                                <div class="stat-label">攻撃検知</div>
                            </div>
                            <div class="stat-value-item stat-block">
                                <span class="stat-number">%s</span>
                                <div class="stat-label">ModSecブロック</div>
                            </div>
                        </div>
                    </div>
                    """, serverName, 
                    WebSecurityUtils.escapeHtml(formatNumber(totalAccess)),
                    WebSecurityUtils.escapeHtml(formatNumber(attackCount)), 
                    WebSecurityUtils.escapeHtml(formatNumber(modsecBlocks))));
            }
        }

        return html.toString();
    }

    /**
     * 条件付きブロックを削除
     * @param html HTML文字列
     * @param condition 条件名
     * @return 処理済みHTML
     */
    private String removeConditionalBlocks(String html, String condition) {
        String startTag = "{{#" + condition + "}}";
        String endTag = "{{/" + condition + "}}";

        int startIndex = html.indexOf(startTag);
        int endIndex = html.indexOf(endTag);

        if (startIndex != -1 && endIndex != -1) {
            return html.substring(0, startIndex) + html.substring(endIndex + endTag.length());
        }

        return html;
    }

    /**
     * 数値をフォーマット（XSS対策適用）
     * @param value 数値オブジェクト
     * @return フォーマット済み文字列
     */
    private String formatNumber(Object value) {
        if (value instanceof Number number) {
            return String.format("%,d", number.intValue());
        }
        return "0";
    }

    /**
     * エラーレスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        String errorTemplate = webConfig.getTemplate("error");
        String html = errorTemplate
            .replace("{{APP_TITLE}}", webConfig.getAppTitle())
            .replace("{{ERROR_CODE}}", String.valueOf(statusCode))
            .replace("{{ERROR_MESSAGE}}", message);

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, html.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes(StandardCharsets.UTF_8));
        }

        logFunction.accept(String.format("エラーレスポンス送信: %d - %s", statusCode, message), "WARN");
    }
}
