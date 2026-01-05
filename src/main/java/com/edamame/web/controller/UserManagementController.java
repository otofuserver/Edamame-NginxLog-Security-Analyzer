package com.edamame.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.service.FragmentService;
import com.edamame.web.service.UserService;
import com.edamame.web.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.edamame.web.exception.DuplicateResourceException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * ユーザー管理関連のAPIコントローラ
 * 管理者のみアクセス可能（AuthenticationServiceのユーザー/ロール判定を利用）
 */
public class UserManagementController implements HttpHandler {

    private final AuthenticationService authService;
    private final UserService userService;
    private final FragmentService fragmentService;
    private final ObjectMapper objectMapper;

    // 正規表現によるメール検証（サーバ側）
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    public UserManagementController(AuthenticationService authService) {
        this.authService = authService;
        this.userService = new UserServiceImpl();
        this.fragmentService = new FragmentService();
        this.objectMapper = new ObjectMapper();
        // LocalDateTime を扱えるようにする
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            // 末尾のスラッシュの差異でマッチしないケースに対応するため正規化
            String normalizedPath = path == null ? "/" : path.replaceAll("/+$", "");
            if (normalizedPath.isEmpty()) normalizedPath = "/";
            AppLogger.debug("UserManagementController called: method=" + method + ", path=" + path + ", normalizedPath=" + normalizedPath);

            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
            if (sessionId == null || authService.validateSession(sessionId) == null) {
                sendJsonError(exchange, 401, "Unauthorized - authentication required");
                return;
            }

            // 認証済みユーザー名を取得。管理者かどうかはルート毎に判断する
            String username = authService.getUsernameBySessionId(sessionId);
            if (username == null) { sendJsonError(exchange, 401, "Unauthorized - authentication required"); return; }
            final boolean isAdmin = userService.isAdmin(username);

            // セキュリティヘッダー
            applyApiSecurityHeaders(exchange);

            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                return;
            }

            if ("GET".equals(method)) {
                if (normalizedPath.equals("/api/fragment/users")) {
                    if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                    handleFragment(exchange);
                    return;
                }
                // 自分のプロフィール取得: GET /api/me/profile
                if (normalizedPath.equals("/api/me/profile")) {
                    handleGetMyProfile(exchange);
                    return;
                }
                // 全ロール一覧取得: GET /api/users/roles
                if (normalizedPath.equals("/api/users/roles")) {
                    if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                    handleListRoles(exchange);
                    return;
                }
                // ユーザー一覧APIは正確な /api/users またはクエリ付きの /api/users? を想定
                if (normalizedPath.equals("/api/users") || (path != null && path.startsWith("/api/users?"))) {
                    if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                    handleUsersApi(exchange);
                    return;
                }
            }

            // ユーザー作成: POST /api/users
            if ("POST".equals(method) && normalizedPath.equals("/api/users")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleCreateUser(exchange);
                return;
            }

            // 自分のプロフィール更新: PUT /api/me/profile
            if ("PUT".equals(method) && normalizedPath.equals("/api/me/profile")) {
                handleUpdateMyProfile(exchange);
                return;
            }

            // メール変更リクエスト作成: POST /api/me/email-change/request
            if ("POST".equals(method) && normalizedPath.equals("/api/me/email-change/request")) {
                handleRequestEmailChange(exchange);
                return;
            }

            // メール変更検証: POST /api/me/email-change/verify
            if ("POST".equals(method) && normalizedPath.equals("/api/me/email-change/verify")) {
                handleVerifyEmailChange(exchange);
                return;
            }

            // PUT /api/users/{username} -> update (管理者のみ)
            if ("PUT".equals(method) && normalizedPath.startsWith("/api/users/")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleUpdateUser(exchange);
                return;
            }

            // DELETE はパスパターンに基づき下で処理（詳細なパターンは下でマッチされる）

            // ユーザー詳細取得: GET /api/users/{username}
            if ("GET".equals(method) && normalizedPath.startsWith("/api/users/")) {
                String[] seg = normalizedPath.split("/");
                // /api/users/{username} の場合
                if (seg.length == 4) {
                    if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                    handleGetUserDetail(exchange);
                    return;
                }
            }

            // ロール追加: POST /api/users/{username}/roles
            if ("POST".equals(method) && normalizedPath.matches("/api/users/[^/]+/roles")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleAddRole(exchange);
                return;
            }

            // パスワードリセット: POST /api/users/{username}/reset-password
            if ("POST".equals(method) && normalizedPath.matches("/api/users/[^/]+/reset-password")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleResetPassword(exchange);
                return;
            }

            // 再送: POST /api/users/{username}/resend-activation
            if ("POST".equals(method) && normalizedPath.matches("/api/users/[^/]+/resend-activation")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleResendActivation(exchange);
                return;
            }

            // 自分のパスワード変更: POST /api/me/password
            if ("POST".equals(method) && normalizedPath.equals("/api/me/password")) {
                handleChangeMyPassword(exchange);
                return;
            }

            // アカウント削除: DELETE /api/users/{username}
            if ("DELETE".equals(method) && normalizedPath.matches("/api/users/[^/]+")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleDeleteUser(exchange);
                return;
            }

            // ロール削除: DELETE /api/users/{username}/roles/{role}
            if ("DELETE".equals(method) && normalizedPath.matches("/api/users/[^/]+/roles/[^/]+")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleRemoveRole(exchange);
                return;
            }

            AppLogger.warn("No route matched: method=" + method + ", path=" + path + ", normalizedPath=" + normalizedPath + ", RemoteAddr=" + exchange.getRemoteAddress());
            sendJsonError(exchange, 404, "Not Found");

        } catch (Exception e) {
            AppLogger.error("UserManagementController error: " + e.getMessage());
            throw e;
        }
    }

    private void handleFragment(HttpExchange exchange) throws IOException {
        String template = fragmentService.getFragmentTemplate("user_management");
        if (template == null) {
            String fallback = "<div class=\"fragment-root\" data-auto-refresh=\"0\"><div class=\"card\"><h2>ユーザー管理</h2><p>テンプレートが見つかりません</p></div></div>";
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            byte[] bytes = fallback.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            return;
        }

        // コンテンツはクライアント側で検索を行うためプレーンで返す
        String wrapped = "<div class=\"fragment-root\" data-auto-refresh=\"0\">" + template + "</div>";
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        byte[] bytes = wrapped.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void handleUsersApi(HttpExchange exchange) throws IOException {
        Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
        String q = queryParams.getOrDefault("q", null);
        int page = 1;
        int size = 20;
        try { if (queryParams.containsKey("page")) page = Integer.parseInt(queryParams.get("page")); } catch (NumberFormatException ignored) {}
        try { if (queryParams.containsKey("size")) size = Integer.parseInt(queryParams.get("size")); } catch (NumberFormatException ignored) {}

        var pageResult = userService.searchUsers(q, page, size);
        Map<String, Object> response = new HashMap<>();
        response.put("total", pageResult.getTotal());
        response.put("page", pageResult.getPage());
        response.put("size", pageResult.getSize());
        response.put("users", pageResult.getItems());

        sendJsonResponse(exchange, 200, response);
    }

    /**
     * ユーザー更新処理（PUT /api/users/{username}）
     */
    private void handleUpdateUser(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        // リクエストボディを読み取り JSON をパース
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }

        String email = payload.containsKey("email") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("email"))) : null;
        boolean enabled = false;
        if (payload.containsKey("enabled")) {
            try { enabled = Boolean.parseBoolean(String.valueOf(payload.get("enabled"))); } catch (Exception ignored) {}
        }

        if (email == null) { sendJsonError(exchange, 400, "email required"); return; }

        // 自分自身の更新は禁止
        String authUserForUpdate = getAuthenticatedUsername(exchange);
        if (authUserForUpdate != null && authUserForUpdate.equals(target)) {
            sendJsonError(exchange, 403, "自分自身のアカウント編集は許可されていません");
            return;
        }

        try {
            boolean ok = userService.updateUser(target, email, enabled);
            if (!ok) { sendJsonError(exchange, 404, "user not found or update failed"); return; }
        } catch (com.edamame.web.exception.AdminRetentionException are) {
            AppLogger.warn("updateUser prevented: " + are.getMessage());
            // 最後の有効な admin を無効化しようとしている場合はクライアントに説明を返す
            sendJsonError(exchange, 400, "admin権限を持つアカウントが他に存在しません。最後のadminを無効化できません。");
            return;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        sendJsonResponse(exchange, 200, resp);
    }

    /**
     * ユーザー削除処理（DELETE /api/users/{username}）
     */
    private void handleDeleteUser(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        // 自分自身の削除は禁止
        String authUserForDelete = getAuthenticatedUsername(exchange);
        if (authUserForDelete != null && authUserForDelete.equals(target)) {
            sendJsonError(exchange, 403, "自分自身のアカウント削除は許可されていません");
            return;
        }

        try {
            boolean ok = userService.deleteUser(target);
            if (!ok) { sendJsonError(exchange, 404, "user not found or delete failed"); return; }
        } catch (com.edamame.web.exception.AdminRetentionException are) {
            AppLogger.warn("deleteUser prevented: " + are.getMessage());
            sendJsonError(exchange, 400, "admin権限を持つアカウントが他に存在しません。最後のadminを削除できません。");
            return;
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("ok", true);
        sendJsonResponse(exchange, 200, resp);
    }

    /**
     * ユーザー詳細取得（ロール含む）
     */
    private void handleGetUserDetail(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        // 自分自身の編集画面取得は禁止
        String authUser = getAuthenticatedUsername(exchange);
        if (authUser != null && authUser.equals(target)) {
            sendJsonError(exchange, 403, "自分自身のアカウント編集は許可されていません");
            return;
        }

        var opt = userService.findByUsername(target);
        if (opt.isEmpty()) { sendJsonError(exchange, 404, "user not found"); return; }
        var dto = opt.get();
        var roles = userService.getRolesForUser(target);
        Map<String, Object> resp = new HashMap<>();
        resp.put("user", dto);
        resp.put("roles", roles);
        resp.put("allRoles", userService.listAllRoles());
        // ユーザーが無効で、未使用かつ期限切れの activation token が存在するかを返す（モーダルで再送ボタン表示制御用）
        try {
            boolean hasExpired = userService.hasExpiredUnusedActivationToken(target);
            resp.put("hasExpiredUnusedActivationToken", hasExpired);
        } catch (Exception e) {
            resp.put("hasExpiredUnusedActivationToken", false);
        }
        sendJsonResponse(exchange, 200, resp);
    }

    /**
     * ロール追加処理: POST /api/users/{username}/roles
     * リクエストボディ: { "role": "rolename" }
     */
    private void handleAddRole(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String role = payload.containsKey("role") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("role"))) : null;
        if (role == null) { sendJsonError(exchange, 400, "role required"); return; }

        boolean ok = userService.addRoleToUser(target, role);
        if (!ok) { sendJsonError(exchange, 400, "failed to add role"); return; }
        sendJsonResponse(exchange, 200, Map.of("ok", true));
    }

    /**
     * ロール削除処理: DELETE /api/users/{username}/roles/{role}
     */
    private void handleRemoveRole(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 6) { sendJsonError(exchange, 400, "invalid path"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);
        String role = WebSecurityUtils.sanitizeInput(seg[5]);

        // 自分自身から admin ロールを削除する操作は禁止
        String authUserForRole = getAuthenticatedUsername(exchange);
        if (authUserForRole != null && authUserForRole.equals(target)) {
            sendJsonError(exchange, 403, "自分自身のロール編集は許可されていません");
            return;
        }

        try {
            boolean ok = userService.removeRoleFromUser(target, role);
            if (!ok) { sendJsonError(exchange, 400, "failed to remove role"); return; }
        } catch (com.edamame.web.exception.AdminRetentionException are) {
            AppLogger.warn("removeRole prevented: " + are.getMessage());
            sendJsonError(exchange, 400, "admin権限を持つアカウントが他に存在しません。最後のadminのロールを削除できません。");
            return;
        }
        sendJsonResponse(exchange, 200, Map.of("ok", true));
    }

    /**
     * パスワードリセット: POST /api/users/{username}/reset-password
     * リクエストボディ: { "password": "plain" } または 空（サーバ生成）
     */
    private void handleResetPassword(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        // ボディが空ならサーバ側で生成して返す
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            String plain = userService.generateAndResetPassword(target);
            if (plain == null) { sendJsonError(exchange, 500, "failed to generate password"); return; }
            sendJsonResponse(exchange, 200, Map.of("password", plain));
            return;
        }

        // クライアントから明示的にパスワードが渡された場合（後方互換）
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(trimmed, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String password = payload.containsKey("password") ? String.valueOf(payload.get("password")) : null;
        if (password == null) { sendJsonError(exchange, 400, "password required"); return; }

        // サーバ側でパスワードポリシーを検証
        if (!isValidPassword(password)) {
            sendJsonError(exchange, 400, "password policy violation: must be >=8 chars, include letter, digit and one of !@#$%&*()-_ and use only allowed characters");
            return;
        }

        boolean ok = userService.resetPassword(target, password);
        if (!ok) { sendJsonError(exchange, 500, "failed to reset password"); return; }
        sendJsonResponse(exchange, 200, Map.of("ok", true));
    }

    /**
     * 再送処理: POST /api/users/{username}/resend-activation
     */
    private void handleResendActivation(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String[] seg = path.split("/");
        if (seg.length < 4) { sendJsonError(exchange, 400, "username missing"); return; }
        String target = WebSecurityUtils.sanitizeInput(seg[3]);

        try {
            boolean ok = userService.resendActivationEmail(target);
            if (!ok) { sendJsonError(exchange, 404, "user not found or resend failed"); return; }
        } catch (Exception e) {
            sendJsonError(exchange, 500, "failed to resend activation email");
            return;
        }

        sendJsonResponse(exchange, 200, Map.of("ok", true));
    }

    private void handleListRoles(HttpExchange exchange) throws IOException {
        var roles = userService.listAllRoles();
        sendJsonResponse(exchange, 200, roles);
    }

    private void handleCreateUser(HttpExchange exchange) throws IOException {
        AppLogger.debug("handleCreateUser called. RemoteAddr=" + exchange.getRemoteAddress() + ", Headers=" + exchange.getRequestHeaders());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        AppLogger.debug("handleCreateUser body=" + body);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String username = payload.containsKey("username") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("username"))) : null;
        String email = payload.containsKey("email") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("email"))) : null;
        // デフォルトで無効（Web経由の登録はデフォルトで無効にする）
        boolean enabled = false;
        if (payload.containsKey("enabled")) {
            try { enabled = Boolean.parseBoolean(String.valueOf(payload.get("enabled"))); } catch (Exception ignored) {}
        }
        if (username == null || username.isEmpty()) { sendJsonError(exchange, 400, "username required"); return; }
        try {
            String plain = userService.createUserWithActivation(username, email, enabled);
            if (plain == null) {
                // サービスが null を返した場合、既に存在する可能性があるため念のため確認
                try {
                    var maybe = userService.findByUsername(username);
                    if (maybe.isPresent()) {
                        AppLogger.warn("createUser fallback: user already exists: " + username);
                        sendJsonError(exchange, 409, "username already exists");
                        return;
                    }
                } catch (Exception ignore) {}
                sendJsonError(exchange, 500, "failed to create user");
                return;
            }
        } catch (DuplicateResourceException dre) {
            AppLogger.warn("createUser duplicate detected: " + dre.getMessage());
            sendJsonError(exchange, 409, "username already exists");
            return;
        } catch (Exception ex) {
            AppLogger.error("createUser error: " + ex.getMessage());
            sendJsonError(exchange, 500, "failed to create user");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(201, 0);
        try (OutputStream os = exchange.getResponseBody()) { os.write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)); }
    }

    private void applyApiSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
    }

    /**
     * コントローラ用の簡易クエリパラメータパーサ
     */
    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] parts = query.split("&");
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx > 0) {
                try {
                    String k = URLDecoder.decode(p.substring(0, idx), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(p.substring(idx + 1), StandardCharsets.UTF_8);
                    map.put(k, v);
                } catch (Exception ignored) {}
            } else {
                try { map.put(URLDecoder.decode(p, StandardCharsets.UTF_8), ""); } catch (Exception ignored) {}
            }
        }
        return map;
    }

    private void sendJsonError(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            String jsonResponse = "{\"error\": \"" + WebSecurityUtils.sanitizeInput(message) + "\"}";
            os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, 0);
        try (OutputStream os = exchange.getResponseBody()) {
            String jsonResponse = objectMapper.writeValueAsString(data);
            os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * リクエストのセッションから認証済みのユーザー名を返す。セッションが無効なら null。
     */
    private String getAuthenticatedUsername(HttpExchange exchange) {
        try {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
            if (sessionId == null) return null;
            return authService.getUsernameBySessionId(sessionId);
        } catch (Exception e) { return null; }
    }

    /**
     * 自分のプロフィール取得: GET /api/me/profile
     */
    private void handleGetMyProfile(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        String username = authService.getUsernameBySessionId(sessionId);
        if (username == null) { sendJsonError(exchange, 401, "Unauthorized"); return; }
        var opt = userService.findByUsername(username);
        if (opt.isEmpty()) { sendJsonError(exchange, 404, "user not found"); return; }
        var dto = opt.get();
        Map<String,Object> resp = new HashMap<>();
        resp.put("user", dto);
        resp.put("loginHistory", userService.getLoginHistory(username, 20));
        sendJsonResponse(exchange, 200, resp);
    }

    /**
     * 自分のプロフィール更新: PUT /api/me/profile
     * サーバ側でメール形式を検証し、メールが変更される場合は所有者確認フローを起動する
     */
    private void handleUpdateMyProfile(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        String username = authService.getUsernameBySessionId(sessionId);
        if (username == null) { sendJsonError(exchange, 401, "Unauthorized"); return; }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String email = payload.containsKey("email") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("email"))) : null;
        String name = payload.containsKey("name") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("name"))) : null;
        if (email == null && name == null) { sendJsonError(exchange, 400, "nothing to update"); return; }

        // サーバ側でメール形式を検証（信頼できないクライアントを考慮）
        if (email != null && !EMAIL_PATTERN.matcher(email).matches()) {
            sendJsonError(exchange, 400, "invalid email format");
            return;
        }

        // 名前のみの更新は既存フローで許可
        if (email == null) {
            boolean ok = userService.updateUser(username, optDefault(email), true);
            if (!ok) { sendJsonError(exchange, 500, "failed to update"); return; }
            sendJsonResponse(exchange, 200, Map.of("ok", true));
            return;
        }

        // メールが現在と同じなら直接更新
        var opt = userService.findByUsername(username);
        if (opt.isPresent()) {
            var dto = opt.get();
            String currentEmail = dto.getEmail();
            if (currentEmail != null && currentEmail.equalsIgnoreCase(email)) {
                boolean ok = userService.updateUser(username, email, true);
                if (!ok) { sendJsonError(exchange, 500, "failed to update"); return; }
                sendJsonResponse(exchange, 200, Map.of("ok", true));
                return;
            }
        }

        // メールが変更される場合は確認フローを開始する
        String reqIp = "";
        try { reqIp = exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress(); } catch (Exception ignored) {}
        long requestId = -1L;
        try { requestId = userService.requestEmailChange(username, email, reqIp); } catch (Exception e) { AppLogger.warn("handleUpdateMyProfile: requestEmailChange エラー: " + e.getMessage()); }
        if (requestId > 0) {
            // 検証が必要であることを示す応答を返す
            Map<String, Object> resp = new HashMap<>();
            resp.put("ok", true);
            resp.put("verificationRequired", true);
            resp.put("requestId", requestId);
            sendJsonResponse(exchange, 200, resp);
            return;
        } else {
            sendJsonError(exchange, 500, "failed to initiate email change request");
            return;
        }
    }

    /**
     * POST /api/me/email-change/request
     * body: { "newEmail": "..." }
     */
    private void handleRequestEmailChange(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        String username = authService.getUsernameBySessionId(sessionId);
        if (username == null) { sendJsonError(exchange, 401, "Unauthorized"); return; }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String newEmail = payload.containsKey("newEmail") ? WebSecurityUtils.sanitizeInput(String.valueOf(payload.get("newEmail"))) : null;
        if (newEmail == null || !EMAIL_PATTERN.matcher(newEmail).matches()) { sendJsonError(exchange, 400, "invalid email format"); return; }
        String reqIp = "";
        try { reqIp = exchange.getRemoteAddress() == null ? "" : exchange.getRemoteAddress().getAddress().getHostAddress(); } catch (Exception ignored) {}
        long requestId = -1L;
        try { requestId = userService.requestEmailChange(username, newEmail, reqIp); } catch (Exception e) { AppLogger.warn("handleRequestEmailChange: userService.requestEmailChange エラー: " + e.getMessage()); }
        if (requestId > 0) {
            sendJsonResponse(exchange, 200, Map.of("ok", true, "requestId", requestId));
        } else {
            sendJsonError(exchange, 500, "failed to create email change request");
        }
    }

    /**
     * POST /api/me/email-change/verify
     * body: { "requestId": 123, "code": "012345" }
     */
    private void handleVerifyEmailChange(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        String username = authService.getUsernameBySessionId(sessionId);
        if (username == null) { sendJsonError(exchange, 401, "Unauthorized"); return; }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        long requestId = -1L;
        try {
            if (payload.containsKey("requestId")) requestId = Long.parseLong(String.valueOf(payload.get("requestId")));
        } catch (Exception ignored) {}
        String code = payload.containsKey("code") ? String.valueOf(payload.get("code")) : null;
        if (requestId <= 0 || code == null) { sendJsonError(exchange, 400, "requestId and code required"); return; }
        boolean ok = false;
        try { ok = userService.verifyEmailChange(username, requestId, code); } catch (Exception e) { AppLogger.warn("handleVerifyEmailChange: userService.verifyEmailChange エラー: " + e.getMessage()); }
        if (ok) sendJsonResponse(exchange, 200, Map.of("ok", true)); else sendJsonError(exchange, 400, "verification failed");
    }

    private static String optDefault(String v) { return v == null? "" : v; }

    /**
     * 自分のパスワード変更: POST /api/me/password
     * ボディ: { "password": "newpass" }
     */
    private void handleChangeMyPassword(HttpExchange exchange) throws IOException {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
        String username = authService.getUsernameBySessionId(sessionId);
        if (username == null) { sendJsonError(exchange, 401, "Unauthorized"); return; }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, Object> payload;
        try { payload = objectMapper.readValue(body, new TypeReference<>(){}); } catch (Exception e) { sendJsonError(exchange, 400, "invalid json"); return; }
        String password = payload.containsKey("password") ? String.valueOf(payload.get("password")) : null;
        if (password == null || password.isEmpty()) { sendJsonError(exchange, 400, "password required"); return; }
        // サーバ側で固定ポリシー検証: 最低8文字、英字・数字・許可記号を含むこと、許可外文字は不可
        if (!isValidPassword(password)) { sendJsonError(exchange, 400, "password policy violation: must be >=8 chars, include letter, digit and one of !@#$%&*()-_ and use only allowed characters"); return; }
        boolean ok = userService.resetPassword(username, password);
        if (!ok) { sendJsonError(exchange, 500, "failed to change password"); return; }
        sendJsonResponse(exchange, 200, Map.of("ok", true));
    }

    /**
     * パスワード文字列がポリシーに合致するか検証する
     * ポリシー: 最低8文字、英字1文字以上、数字1文字以上、許可記号(!@#$%&*()-_)のうち1文字以上、許可外文字は不可
     */
    private boolean isValidPassword(String pw) {
        if (pw == null) return false;
        boolean lengthOk = pw.length() >= 8;
        boolean hasLetter = pw.matches(".*[A-Za-z].*");
        boolean hasDigit = pw.matches(".*\\d.*");
        // 記号として @ を許可する
        boolean hasSymbol = pw.matches(".*[!@#$%&*()\\-@].*");
        // 全体が許可文字だけで構成されているか（@ を許可）
        boolean allAllowed = pw.matches("^[A-Za-z0-9!@#$%&*()\\-@_]+$");
        return lengthOk && hasLetter && hasDigit && hasSymbol && allAllowed;
    }
}
