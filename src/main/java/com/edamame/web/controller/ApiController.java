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
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
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
                if ("/api/url-threats".equals(path)) {
                    handleUrlThreatsPostApi(exchange);
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
                case "url-threats" -> handleUrlThreatsApi(exchange);
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

        // セッションユーザーを特定
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
        if (sessionInfo == null) {
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
        }
        String username = sessionInfo.getUsername();

        var servers = dataService.getServerList();
        com.edamame.web.service.UserService userService = new com.edamame.web.service.impl.UserServiceImpl();
        boolean isAdmin = false;
        try { isAdmin = userService.isAdmin(username); } catch (Exception ignored) {}

        // viewer（上位ロール含む）を持つサーバーのみ + qフィルタ
        java.util.List<Map<String, Object>> filtered = new java.util.ArrayList<>();
        String lowerQ = q.toLowerCase();
        for (Map<String, Object> s : servers) {
            Object nameObj = s.get("serverName");
            String name = nameObj == null ? "" : nameObj.toString();
            if (name.isEmpty()) continue;
            String viewerRole = name + "_viewer";
            boolean allowed = isAdmin || userService.hasRoleIncludingHigher(username, viewerRole);
            if (!allowed) continue;
            if (!q.isEmpty() && !name.toLowerCase().contains(lowerQ)) continue;
            filtered.add(s);
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
            case "main" -> {
                // main フラグメントはテンプレート内に APP_TITLE / APP_VERSION / MENU_HTML 等のプレースホルダを含む
                String tpl = fragmentService.getFragmentTemplate("main");
                if (tpl != null) {
                    // セッションからユーザー名を取得して isAdmin を判定
                    String cookieHeaderLocal = exchange.getRequestHeaders().getFirst("Cookie");
                    String sessionIdLocal = com.edamame.web.config.WebConstants.extractSessionId(cookieHeaderLocal);
                    String usernameLocal = null;
                    boolean isAdminLocal = false;
                    if (sessionIdLocal != null) {
                        var sessionInfoLocal = authService.validateSession(sessionIdLocal);
                        if (sessionInfoLocal != null) {
                            usernameLocal = sessionInfoLocal.getUsername();
                            try {
                                isAdminLocal = new com.edamame.web.service.impl.UserServiceImpl().isAdmin(usernameLocal);
                            } catch (Exception ignored) {}
                        }
                    }
                    String filled = tpl
                        .replace("{{APP_TITLE}}", WebSecurityUtils.escapeHtml(new com.edamame.web.config.WebConfig().getAppTitle()))
                        .replace("{{APP_VERSION}}", WebSecurityUtils.escapeHtml(new com.edamame.web.config.WebConfig().getAppVersion()))
                        .replace("{{MENU_HTML}}", MainController.generateMenuHtml(usernameLocal == null ? "" : usernameLocal, isAdminLocal));
                    html = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"main\">" + filled + "</div>";
                }
            }
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
        // フォールバック：主要なテンプレートプレースホルダが残っている場合はここで置換
        try {
            com.edamame.web.config.WebConfig cfg = new com.edamame.web.config.WebConfig();
            String appTitle = WebSecurityUtils.escapeHtml(cfg.getAppTitle());
            String appVersion = WebSecurityUtils.escapeHtml(cfg.getAppVersion());
            // メニューはセッションから判断する（既に上で判定済みの usernameLocal/isAdminLocal が存在するかもしれない）
            // ここでは簡易的に空メニューを生成し、MainController のユーティリティを利用して埋める
            String menuHtmlFallback = "";
            try {
                // attempt to get username/isAdmin from request attributes if set by AuthenticationFilter
                Object unameAttr = exchange.getAttribute(com.edamame.web.config.WebConstants.REQUEST_ATTR_USERNAME);
                Object isAdminAttr = exchange.getAttribute(com.edamame.web.config.WebConstants.REQUEST_ATTR_IS_ADMIN);
                String uname = unameAttr == null ? "" : String.valueOf(unameAttr);
                boolean isAdm = Boolean.TRUE.equals(isAdminAttr);
                menuHtmlFallback = MainController.generateMenuHtml(uname, isAdm);
            } catch (Exception ignored) { }

            html = html.replace("{{APP_TITLE}}", appTitle)
                       .replace("{{APP_VERSION}}", appVersion)
                       .replace("{{MENU_HTML}}", menuHtmlFallback)
                       .replace("{{CURRENT_VIEW}}", WebSecurityUtils.escapeHtml(name == null ? "" : name));
        } catch (Exception ignored) { /* 最終フォールバックは無視 */ }

         exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
         exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
         byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(200, bytes.length);
         try (OutputStream os = exchange.getResponseBody()) {
             os.write(bytes);
         }
    }

    /**
     * URL脅威度APIを処理（サーバー別・脅威度フィルタ対応）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleUrlThreatsApi(HttpExchange exchange) throws IOException {
        String rawQuery = exchange.getRequestURI().getQuery();
        java.util.Map<String, String> params = WebSecurityUtils.parseQueryParams(rawQuery);
        String server = params.getOrDefault("server", "").trim();
        String filter = params.getOrDefault("filter", "all").trim().toLowerCase();
        String q = params.getOrDefault("q", "").trim();
        String sort = params.getOrDefault("sort", "priority").trim();
        String order = params.getOrDefault("order", "asc").trim().toLowerCase();
         if (!java.util.Set.of("all", "safe", "danger", "caution", "unknown").contains(filter)) {
             filter = "all";
         }
        if (!java.util.Set.of("asc", "desc").contains(order)) {
            order = "asc";
        }
         int page = 1;
         int size = 20;
         try { if (params.containsKey("page")) page = Math.max(1, Integer.parseInt(params.get("page"))); } catch (Exception ignored) {}
         try { if (params.containsKey("size")) size = Math.max(1, Integer.parseInt(params.get("size"))); } catch (Exception ignored) {}
         String sanitizedServer = server.isEmpty() ? null : WebSecurityUtils.sanitizeInput(server);
         String sanitizedQuery = q.isEmpty() ? "" : WebSecurityUtils.sanitizeInput(q);
         String sanitizedSort = sort.isEmpty() ? "priority" : WebSecurityUtils.sanitizeInput(sort);

         // セッションユーザーと権限を判定
         String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
         String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
         var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
         if (sessionInfo == null) {
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
         }
         String username = sessionInfo.getUsername();
         var userService = new com.edamame.web.service.impl.UserServiceImpl();
         boolean isAdmin = false;
         try { isAdmin = userService.isAdmin(username); } catch (Exception ignored) {}
         if (sanitizedServer == null || sanitizedServer.isEmpty()) {
             sendJsonError(exchange, 400, "server parameter required");
             return;
         }
         String viewerRole = sanitizedServer + "_viewer";
         boolean canView = isAdmin || userService.hasRoleIncludingHigher(username, viewerRole);
         if (!canView) {
             sendJsonError(exchange, 403, "Forbidden - viewer role required");
             return;
         }
         boolean canOperate = isAdmin || userService.hasRoleIncludingHigher(username, sanitizedServer + "_operator");

        try {
            var threats = dataService.getUrlThreats(sanitizedServer, filter, sanitizedQuery, sanitizedSort, order);
            if (threats == null) threats = java.util.Collections.emptyList();
            int total = threats.size();
            int from = Math.min((page - 1) * size, total);
            int to = Math.min(from + size, total);
            var pageList = threats.subList(from, to);
            var sanitized = sanitizeListData(pageList);
            int totalPages = size == 0 ? 1 : Math.max(1, (int)Math.ceil((double) total / size));
            Map<String, Object> response = Map.of(
                "items", sanitized,
                "count", sanitized.size(),
                "total", total,
                "page", page,
                "size", size,
                "totalPages", totalPages,
                "canOperate", canOperate
            );
            sendJsonResponse(exchange, 200, response);
            AppLogger.debug("URL脅威度API呼び出し完了 (count=" + sanitized.size() + ")");
        } catch (Exception e) {
            AppLogger.error("URL脅威度API処理中にエラー: " + stackTraceToString(e));
            sendJsonError(exchange, 500, "URL脅威度の取得に失敗しました");
        }
    }

    /**
     * URL脅威度APIへの POST リクエストを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleUrlThreatsPostApi(HttpExchange exchange) throws IOException {
        // リクエストボディの読み込み
        String requestBody;
        try (InputStream is = exchange.getRequestBody()) {
            requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            sendJsonError(exchange, 400, "Invalid request body");
            return;
        }

        // JSON パース
        JSONObject json;
        try {
            json = new JSONObject(requestBody);
        } catch (Exception e) {
            System.out.println("[ApiController] url-threats POST invalid JSON body: " + e.getMessage());
            sendJsonError(exchange, 400, "Invalid JSON format");
            return;
        }

        System.out.println("[ApiController] url-threats POST received path=" + exchange.getRequestURI().getPath() + " method=" + exchange.getRequestMethod());

        String server = json.optString("serverName", null);
        String method = json.optString("method", null);
        String fullUrl = json.optString("fullUrl", null);
        String action = json.optString("action", "");
        String note = json.optString("note", "");
        if (server == null || method == null || fullUrl == null || action.isBlank()) {
            AppLogger.warn("URL脅威度POST: 必須パラメータ不足 server=" + server + " method=" + method + " url=" + fullUrl + " action=" + action);
            sendJsonError(exchange, 400, "Missing required fields");
            return;
        }
        server = server.trim();
        method = method.trim();
        fullUrl = fullUrl.trim();

        // 認証・認可
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
        if (sessionInfo == null) {
            AppLogger.warn("URL脅威度POST: 認証エラー (session null)");
            System.out.println("[ApiController] url-threats POST auth failed (session null)");
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
        }
        String username = sessionInfo.getUsername();
        AppLogger.info("URL脅威度POST開始: user=" + username + " server=" + server + " method=" + method + " action=" + action);
        System.out.println("[ApiController] url-threats POST start user=" + username + " server=" + server + " method=" + method + " action=" + action);
        var userService = new com.edamame.web.service.impl.UserServiceImpl();
        boolean isAdmin = false;
        try { isAdmin = userService.isAdmin(username); } catch (Exception ignored) {}
        boolean allowed = isAdmin || userService.hasRoleIncludingHigher(username, server + "_operator");
        if (!allowed) {
            AppLogger.warn("URL脅威度POST: 権限不足 user=" + username + " server=" + server);
            System.out.println("[ApiController] url-threats POST forbidden user=" + username + " server=" + server);
            sendJsonError(exchange, 403, "Forbidden - operator role required");
            return;
        }

        String sanitizedNote = WebSecurityUtils.sanitizeInput(note == null ? "" : note);
        String normalizedAction = action.toLowerCase();
        if (!java.util.Set.of("danger", "safe", "clear", "note").contains(normalizedAction)) {
            AppLogger.warn("URL脅威度POST: action不正 " + action);
            System.out.println("[ApiController] url-threats POST invalid action " + action);
            sendJsonError(exchange, 400, "Invalid action");
            return;
        }

        boolean updated = dataService.updateUrlThreatCategory(server, method, fullUrl, normalizedAction, sanitizedNote);
        if (!updated) {
            AppLogger.warn("URL脅威度POST: 更新対象なし server=" + server + " method=" + method + " url=" + fullUrl + " action=" + normalizedAction);
            System.out.println("[ApiController] url-threats POST update failed (not found) server=" + server + " method=" + method + " url=" + fullUrl + " action=" + normalizedAction);
            sendJsonError(exchange, 404, "url threat not found");
            return;
        }

        Map<String, Object> response = Map.of("success", true);
        sendJsonResponse(exchange, 200, response);
        AppLogger.info("URL脅威度POST完了: user=" + username + " server=" + server + " method=" + method + " action=" + normalizedAction);
        System.out.println("[ApiController] url-threats POST success user=" + username + " server=" + server + " method=" + method + " action=" + normalizedAction);
    }

    /**
     * Mapデータをサニタイズ
     * @param data 元データ
     * @return サニタイズ済みデータ
     */
    private Map<String, Object> sanitizeMapData(Map<String, Object> data) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String key = entry.getKey() == null ? "" : WebSecurityUtils.sanitizeInput(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof String str) {
                if ("fullUrl".equalsIgnoreCase(key)) {
                    sanitized.put(key, str); // URLはフロントでtextContent描画するためエスケープ不要
                } else {
                    sanitized.put(key, WebSecurityUtils.sanitizeInput(str));
                }
            } else if (value instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> sub = (Map<String, Object>) map;
                sanitized.put(key, sanitizeMapData(sub));
            } else if (value instanceof java.util.List<?> list) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> l = (java.util.List<Map<String, Object>>) list;
                sanitized.put(key, sanitizeListData(l));
            } else {
                sanitized.put(key, value); // null もそのまま許容
            }
        }
        return sanitized;
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

    private String stackTraceToString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
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

