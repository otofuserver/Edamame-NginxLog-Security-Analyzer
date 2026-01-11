package com.edamame.web.controller;

import com.edamame.web.config.WebConfig;
import com.edamame.web.service.DataService;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.config.WebConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.edamame.security.tools.AppLogger;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.edamame.web.service.FragmentService; // 追加: フラグメント取得用

/**
 * ダッシュボードコントローラークラス
 * ダッシュボード画面の表示を担当（XSS対策強化版）
 */
public class DashboardController implements HttpHandler {

    private final DataService dataService;
    private final WebConfig webConfig;

    /**
     * コンストラクタ
     * @param dataService データサービス
     */
    public DashboardController(DataService dataService) {
        this.dataService = dataService;
        this.webConfig = new WebConfig(); // WebConfigを内部で初期化
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        handleDashboard(exchange);
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
            String username = (String) exchange.getAttribute(WebConstants.REQUEST_ATTR_USERNAME);
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
                AppLogger.warn("不正なリクエストを検知してブロックしました: " + exchange.getRequestURI());
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
            // サーバ側レンダリング時は dashboard 固定ビューを想定する

            // HTMLを生成（断片はDashboard側で作り、フルページの置換はMain側に一元化）
            boolean isAdmin = Boolean.TRUE.equals(exchange.getAttribute(WebConstants.REQUEST_ATTR_IS_ADMIN));
            String innerFragment = generateDashboardFragmentHtml(dashboardData);
            // AuthenticationFilter がリクエスト属性 'scriptNonce' とレスポンスヘッダを設定している想定
            String html = MainController.renderFullPage(innerFragment, username, isAdmin, "dashboard");

            // レスポンス送信
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(html.getBytes(StandardCharsets.UTF_8));
            }

            AppLogger.debug("ダッシュボード画面表示完了（セキュリティ強化版）");

        } catch (Exception e) {
            AppLogger.error("ダッシュボード処理エラー: " + e.getMessage());
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
        if (WebSecurityUtils.detectXSS(userAgent)) {
            AppLogger.warn("不正なUser-Agentを検知: " + userAgent);
            return false;
        }

        // Refererのチェック
        if (WebSecurityUtils.detectXSS(referer)) {
            AppLogger.warn("不正なRefererを検知: " + referer);
            return false;
        }

        // クエリパラメータのチェック
        if ((WebSecurityUtils.detectXSS(query) || WebSecurityUtils.detectSQLInjection(query))) {
            AppLogger.warn("不正なクエリパラメータを検知: " + query);
            return false;
        }

        return true;
    }

    /**
     * ダッシュボードの断片HTMLを生成して返す。フルページのテンプレート置換は MainController に委譲する。
     * @param data ダッシュボードデータ
     * @return 断片HTML（fragment-root でラップ済み）
     */
    private String generateDashboardFragmentHtml(Map<String, Object> data) {
        // 各部分HTMLを生成
        String dashboardContent = "";
        dashboardContent += generateSecureServerStatsHtml(data.get("serverStats"));
        dashboardContent += generateSecureAlertsHtml(data.get("recentAlerts"));
        dashboardContent += generateSecureServersHtml(data.get("serverList"));
        dashboardContent += generateSecureAttackTypesHtml(data.get("attackTypes"));

        FragmentService fragmentService = new FragmentService();
        String fragmentTemplate = fragmentService.getFragmentTemplate("dashboard");
        if (fragmentTemplate != null) {
            String filled = fragmentTemplate.replace("{{CONTENT}}", dashboardContent);
            return "<div class=\"fragment-root\" data-auto-refresh=\"30\" data-fragment-name=\"dashboard\">" + filled + "</div>";
        } else {
            return "<div class=\"fragment-root\" data-auto-refresh=\"0\">" + dashboardContent + "</div>";
        }
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

                // DataService 側では isActive=true のみを返す想定だが、念のため isActive をチェックして無効サーバはスキップする
                boolean isActive = Boolean.TRUE.equals(serverMap.getOrDefault("isActive", Boolean.FALSE));
                if (!isActive) continue; // 無効サーバは表示しない

                String name = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("serverName", "unknown"));
                String description = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("serverDescription", ""));
                String status = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("status", "unknown"));
                String lastLogReceived = WebSecurityUtils.sanitizeInput((String) serverMap.getOrDefault("lastLogReceived", "未記録"));
                Object accessCount = serverMap.getOrDefault("todayAccessCount", serverMap.getOrDefault("today_access_count", serverMap.getOrDefault("todayAccess", 0)));

                String statusClass = "online".equals(status) ? "online" : "offline";

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

        AppLogger.warn(String.format("エラーレスポンス送信: %d - %s", statusCode, message));
    }
}
