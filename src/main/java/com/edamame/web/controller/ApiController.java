package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.service.DataService;
import com.edamame.web.service.FragmentService;
import com.edamame.web.security.WebSecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * APIコントローラークラス
 * REST API エンドポイントの処理を担当
 */
public class ApiController implements HttpHandler {

    private final DataService dataService;
    private final ObjectMapper objectMapper;
    private final FragmentService fragmentService;
    private final com.edamame.web.security.AuthenticationService authService; // API内で認証を行う

    /**
     * コンストラクタ（API用）
     * @param dataService データサービス
     * @param authService 認証サービス（API内でセッション検証用）
     */
    public ApiController(DataService dataService, com.edamame.web.security.AuthenticationService authService) {
        this.dataService = dataService;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.fragmentService = new FragmentService();
        this.authService = authService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        handleApi(exchange);
    }

    /**
     * APIリクエストを処理（XSS対策強化版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleApi(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            AppLogger.debug("API呼び出し: method=" + method + ", path=" + path);

            // APIはAJAXで呼ばれるため未認証時はリダイレクトではなくJSON 401を返す
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
            if (sessionId == null || authService.validateSession(sessionId) == null) {
                sendJsonError(exchange, 401, "Unauthorized - authentication required");
                return;
            }

            // セキュリティヘッダーを設定（API版）
            applyApiSecurityHeaders(exchange);

            // リクエストのセキュリティ検証
            if (!validateApiRequest(exchange)) {
                AppLogger.warn("不正なAPIリクエストを検知してブロックしました: " + path);
                sendJsonError(exchange, 400, "Invalid Request - Security validation failed");
                return;
            }

            // OPTIONS リクエスト（プリフライト）への対応
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                return;
            }

            // POST を受け付けるエンドポイント（サーバー操作など）
            if ("POST".equals(method)) {
                // サーバー操作: /api/servers/{id}/{action}
                if (path != null && path.startsWith("/api/servers/")) {
                    handleServersPostApi(exchange);
                    return;
                }
                sendJsonError(exchange, 405, "Method Not Allowed");
                return;
            }

            // GETメソッドのみ許可（POST は上で処理）
            if (!"GET".equals(method)) {
                sendJsonError(exchange, 405, "Method Not Allowed");
                return;
            }

            // パスに基づいてAPIエンドポイントを振り分け
            String endpoint = extractEndpoint(path);
            AppLogger.debug("抽出endpoint=" + endpoint + " (from path=" + path + ")");
            switch (endpoint) {
                case "stats" -> handleStatsApi(exchange);
                case "alerts" -> handleAlertsApi(exchange);
                case "servers" -> handleServersApi(exchange);
                case "attack-types" -> handleAttackTypesApi(exchange);
                case "health" -> handleHealthApi(exchange);
                case "fragment" -> handleFragmentApi(exchange);
                // エージェント関連APIを削除（TCP通信に変更のため不要）
                default -> sendJsonError(exchange, 404, "API endpoint not found: " + WebSecurityUtils.sanitizeInput(endpoint));
            }

        } catch (Exception e) {
            AppLogger.error("APIリクエスト処理中にエラー: " + e.getMessage());
            throw e;
        }
    }

    /**
     * APIセキュリティヘッダーを適用
     * @param exchange HTTPエクスチェンジ
     */
    private void applyApiSecurityHeaders(HttpExchange exchange) {
        // API用セキュリティヘッダー
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        
        // CORS ヘッダー（制限的に設定）
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS, POST");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");
        
        // キャッシュ制御
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
    }

    /**
     * APIリクエストのセキュリティ検証
     * @param exchange HTTPエクスチェンジ
     * @return 安全なリクエストの場合true
     */
    private boolean validateApiRequest(HttpExchange exchange) {
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();

        // User-Agentのチェック
        if (WebSecurityUtils.detectXSS(userAgent)) {
            AppLogger.warn("APIで不正なUser-Agentを検知: " + userAgent);
            return false;
        }

        // Refererのチェック
        if (WebSecurityUtils.detectXSS(referer)) {
            AppLogger.warn("APIで不正なRefererを検知: " + referer);
            return false;
        }

        // パスのチェック
        if (WebSecurityUtils.detectXSS(path) || WebSecurityUtils.detectSQLInjection(path)) {
            AppLogger.warn("APIで不正なパスを検知: " + path);
            return false;
        }

        // クエリパラメータのチェック
        if (WebSecurityUtils.detectXSS(query) || WebSecurityUtils.detectSQLInjection(query)) {
            AppLogger.warn("APIで不正なクエリパラメータを検知: " + query);
            return false;
        }

        return true;
    }

    /**
     * パスからエンドポイント名を抽出（セキュア版）
     * @param path リクエストパス
     * @return エンドポイント名
     */
    private String extractEndpoint(String path) {
        if (path == null) {
            return "";
        }

        // path は /api/XXX/... の形式を想定しているため、まずスラッシュで分割する
        // （sanitizeInput は '/' をエンティティに置換するためここで使用しない）
        String[] segments = path.split("/");
        if (segments.length >= 3) {
            // 個々のセグメントは後で使用する際にサニタイズする
            return WebSecurityUtils.sanitizeInput(segments[2]); // /api/XXX の XXX 部分
        }
        return "";
    }

    /**
     * 統計情報APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleStatsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = dataService.getApiStats();
        // データをサニタイズ
        Map<String, Object> sanitizedStats = sanitizeMapData(stats);
        sendJsonResponse(exchange, 200, sanitizedStats);
        AppLogger.debug("統計情報API呼び出し完了（セキュア版）");
    }

    /**
     * アラート情報APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAlertsApi(HttpExchange exchange) throws IOException {
        // クエリパラメータからlimitを取得（デフォルト20件）
        String query = exchange.getRequestURI().getQuery();
        int limit = parseSecureIntParameter(query);

        var alerts = dataService.getRecentAlerts(limit);
        
        // アラートデータをサニタイズ
        var sanitizedAlerts = sanitizeListData(alerts);
        
        Map<String, Object> response = Map.of(
            "alerts", sanitizedAlerts,
            "total", sanitizedAlerts.size(),
            "limit", limit
        );

        sendJsonResponse(exchange, 200, response);
        AppLogger.debug("アラート情報API呼び出し完了（セキュア版） (件数: " + sanitizedAlerts.size() + ")");
    }

    /**
     * サーバー関連の POST 操作ハンドラ（/api/servers/{id}/disable など）
     */
    private void handleServersPostApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] segments = path.split("/");
        AppLogger.debug("handleServersPostApi segments=" + java.util.Arrays.toString(segments));
        if (segments.length < 5) {
            sendJsonError(exchange, 400, "invalid servers action path");
            return;
        }
        String idStr = segments[3];
        String action = segments[4];
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            sendJsonError(exchange, 400, "invalid server id");
            return;
        }

        // 認可チェック: API 呼び出し元のセッションを検証し、管理者でなければ 403 を返す
        // 実行者のユーザー名をログに残す（認可チェックを行い、管理者でなければ403を返す）
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
        if (sessionInfo == null) {
            // 未認証なら401
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
        }
        String username = sessionInfo.getUsername();
        AppLogger.info("ユーザー操作: " + username + " がサーバー操作を要求しました: id=" + id + ", action=" + action);

        // 管理者権限チェック（UserServiceImpl を利用）
        boolean isAdmin = false;
        try {
            isAdmin = new com.edamame.web.service.impl.UserServiceImpl().isAdmin(username);
        } catch (Exception e) {
            AppLogger.warn("isAdmin チェック失敗: " + e.getMessage());
        }
        if (!isAdmin) {
            AppLogger.warn("Forbidden: user " + username + " attempted server action without admin role");
            sendJsonError(exchange, 403, "Forbidden - admin required");
            return;
        }

        boolean ok = false;
        try {
            switch (action) {
                case "disable" -> ok = dataService.disableServerById(id);
                case "enable" -> ok = dataService.enableServerById(id);
                default -> { sendJsonError(exchange, 404, "server action not found: " + action); return; }
            }
        } catch (Exception e) {
            AppLogger.error("サーバー操作実行中にエラー: " + e.getMessage());
            sendJsonError(exchange, 500, "internal server error");
            return;
        }

        if (!ok) {
            sendJsonError(exchange, 500, "operation failed");
            return;
        }

        Map<String, Object> res = Map.of("success", true);
        sendJsonResponse(exchange, 200, res);
    }

    /**
     * サーバー一覧API（検索 q / ページ page / サイズ size をサポート）
     */
    private void handleServersApi(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        java.util.Map<String, String> params = WebSecurityUtils.parseQueryParams(query);
        String q = params.getOrDefault("q", "").trim();
        int page = 1; int size = 20;
        try { if (params.containsKey("page")) page = Math.max(1, Integer.parseInt(params.get("page"))); } catch (Exception ignored) {}
        try { if (params.containsKey("size")) size = Math.max(1, Integer.parseInt(params.get("size"))); } catch (Exception ignored) {}

        var servers = dataService.getServerList();
        // フィルタリング（サーバー名に q を部分一致）
        java.util.List<Map<String, Object>> filtered = new java.util.ArrayList<>();
        if (q.isEmpty()) {
            filtered.addAll(servers);
        } else {
            String lowerQ = q.toLowerCase();
            for (Map<String, Object> s : servers) {
                Object nameObj = s.get("serverName");
                String name = nameObj != null ? nameObj.toString().toLowerCase() : "";
                if (name.contains(lowerQ)) filtered.add(s);
            }
        }

        int total = filtered.size();
        int from = Math.min((page - 1) * size, total);
        int to = Math.min(from + size, total);
        java.util.List<Map<String, Object>> pageList = filtered.subList(from, to);

        var sanitized = sanitizeListData(pageList);
        Map<String, Object> response = Map.of(
            "servers", sanitized,
            "total", total,
            "page", page,
            "size", size
        );
        sendJsonResponse(exchange, 200, response);
        AppLogger.debug("サーバー情報API呼び出し完了（セキュア版） (returned=" + pageList.size() + ", total=" + total + ")");
    }

    /**
     * 攻撃タイプ統計APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAttackTypesApi(HttpExchange exchange) throws IOException {
        var attackTypes = dataService.getAttackTypeStats();
        
        // 攻撃タイプデータをサニタイズ
        var sanitizedAttackTypes = sanitizeListData(attackTypes);
        
        Map<String, Object> response = Map.of(
            "attackTypes", sanitizedAttackTypes,
            "total", sanitizedAttackTypes.size()
        );

        sendJsonResponse(exchange, 200, response);
        AppLogger.debug("攻撃タイプ統計API呼び出し完了（セキュア版）");
    }

    /**
     * ヘルスチェックAPIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleHealthApi(HttpExchange exchange) throws IOException {
        boolean dbConnected = dataService.isConnectionValid();
        Map<String, Object> health = Map.of(
            "db_connected", dbConnected,
            "version", "v1.0.1"
        );

        sendJsonResponse(exchange, 200, health);
        AppLogger.debug("ヘルスチェックAPI呼び出し完了");
    }

    /**
     * フラグメント取得APIを処理
     * パス例: /api/fragment/servers
     */
    private void handleFragmentApi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        AppLogger.debug("handleFragmentApi path=" + path);
        String[] segments = path.split("/");
        AppLogger.debug("fragment segments length=" + segments.length + ", segments=" + java.util.Arrays.toString(segments));
        if (segments.length < 4) {
            AppLogger.warn("fragment name missing, segments=" + java.util.Arrays.toString(segments));
            sendJsonError(exchange, 400, "fragment name missing");
            return;
        }
        String name = WebSecurityUtils.sanitizeInput(segments[3]);
        AppLogger.debug("fragment requested name=" + name);

        // ここでは簡易的に 'dashboard' と 'test' をサポート
        String html = null;
        switch (name) {
            case "dashboard" -> html = fragmentService.dashboardFragment(dataService.getDashboardStats());
            case "test" -> html = fragmentService.testFragment();
            case "servers" -> {
                // server_management.html を返却（フラグメント名とファイル名が異なるため明示的にマッピング）
                String tpl = fragmentService.getFragmentTemplate("server_management");
                if (tpl != null) {
                    html = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"servers\">" + tpl + "</div>";
                }
            }
            default -> {
                // フォールバック: フラグメント名と同名のテンプレートがあれば返す
                String tpl = fragmentService.getFragmentTemplate(name);
                if (tpl != null) {
                    html = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"" + name + "\">" + tpl + "</div>";
                }
            }
        }

        if (html == null) {
            sendJsonError(exchange, 404, "fragment not found: " + name);
            return;
        }

         exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
         exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
         byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(200, bytes.length);
         try (OutputStream os = exchange.getResponseBody()) {
             os.write(bytes);
         }
    }

    /**
     * Mapデータをサニタイズ
     * @param data 元データ
     * @return サニタイズ済みデータ
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMapData(Map<String, Object> data) {
        return data.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                entry -> WebSecurityUtils.sanitizeInput(entry.getKey()),
                entry -> {
                    Object value = entry.getValue();
                    if (value instanceof String str) {
                        return WebSecurityUtils.sanitizeInput(str);
                    } else if (value instanceof Map<?, ?> map) {
                        return sanitizeMapData((Map<String, Object>) map);
                    } else if (value instanceof java.util.List<?> list) {
                        return sanitizeListData((java.util.List<Map<String, Object>>) list);
                    }
                    return value;
                }
            ));
    }

    /**
     * Listデータをサニタイズ
     * @param data 元データ
     * @return サニタイズ済みデータ
     */

    private java.util.List<Map<String, Object>> sanitizeListData(java.util.List<Map<String, Object>> data) {
        return data.stream()
            .map(this::sanitizeMapData)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * セキュアなクエリパラメータ解析（limit専用）
     * @param query クエリ文字列
     * @return 解析された整数値（デフォルト20）
     */
    private int parseSecureIntParameter(String query) {
        final String paramName = "limit";
        final int defaultValue = 20;
        if (query == null || query.isEmpty()) {
            return defaultValue;
        }

        // クエリをサニタイズ
        String sanitizedQuery = WebSecurityUtils.sanitizeInput(query);

        for (String param : sanitizedQuery.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && paramName.equals(keyValue[0])) {
                try {
                    int value = Integer.parseInt(keyValue[1]);
                    return Math.max(value, 1); // 最小値1
                } catch (NumberFormatException e) {
                    AppLogger.warn("無効な整数値を検知: " + keyValue[1]);
                }
            }
        }
        return defaultValue;
    }

    /**
     * JSON形式でエラーレスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendJsonError(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            String jsonResponse = "{\"error\": \"" + WebSecurityUtils.sanitizeInput(message) + "\"}";
            os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * JSON形式でレスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param data レスポンスデータ
     * @throws IOException I/O例外
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            String jsonResponse = objectMapper.writeValueAsString(data);
            os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
        }
    }
}

