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

/**
 * ユーザー管理関連のAPIコントローラ
 * 管理者のみアクセス可能（AuthenticationServiceのユーザー/ロール判定を利用）
 */
public class UserManagementController implements HttpHandler {

    private final AuthenticationService authService;
    private final UserService userService;
    private final FragmentService fragmentService;
    private final ObjectMapper objectMapper;

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

            // 管理者権限チェック
            String username = authService.getUsernameBySessionId(sessionId);
            if (username == null || !userService.isAdmin(username)) {
                sendJsonError(exchange, 403, "Forbidden - admin role required");
                return;
            }

            // セキュリティヘッダー
            applyApiSecurityHeaders(exchange);

            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                return;
            }

            if ("GET".equals(method)) {
                if (normalizedPath.equals("/api/fragment/users")) {
                    handleFragment(exchange);
                    return;
                }
                // 全ロール一覧取得: GET /api/users/roles
                if (normalizedPath.equals("/api/users/roles")) {
                    handleListRoles(exchange);
                    return;
                }
                // ユーザー一覧APIは正確な /api/users またはクエリ付きの /api/users? を想定
                if (normalizedPath.equals("/api/users") || (path != null && path.startsWith("/api/users?"))) {
                    handleUsersApi(exchange);
                    return;
                }
            }

            // ユーザー作成: POST /api/users
            if ("POST".equals(method) && normalizedPath.equals("/api/users")) {
                handleCreateUser(exchange);
                return;
            }

            // PUT /api/users/{username} -> update
            if ("PUT".equals(method) && normalizedPath.startsWith("/api/users/")) {
                handleUpdateUser(exchange);
                return;
            }

            // DELETE はパスパターンに基づき下で処理（詳細なパターンは下でマッチされる）

            // ユーザー詳細取得: GET /api/users/{username}
            if ("GET".equals(method) && normalizedPath.startsWith("/api/users/")) {
                String[] seg = normalizedPath.split("/");
                // /api/users/{username} の場合
                if (seg.length == 4) {
                    handleGetUserDetail(exchange);
                    return;
                }
            }

            // ロール追加: POST /api/users/{username}/roles
            if ("POST".equals(method) && normalizedPath.matches("/api/users/[^/]+/roles")) {
                handleAddRole(exchange);
                return;
            }

            // パスワードリセット: POST /api/users/{username}/reset-password
            if ("POST".equals(method) && normalizedPath.matches("/api/users/[^/]+/reset-password")) {
                handleResetPassword(exchange);
                return;
            }

            // アカウント削除: DELETE /api/users/{username}
            if ("DELETE".equals(method) && normalizedPath.matches("/api/users/[^/]+")) {
                handleDeleteUser(exchange);
                return;
            }

            // ロール削除: DELETE /api/users/{username}/roles/{role}
            if ("DELETE".equals(method) && normalizedPath.matches("/api/users/[^/]+/roles/[^/]+")) {
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

        boolean ok = userService.resetPassword(target, password);
        if (!ok) { sendJsonError(exchange, 500, "failed to reset password"); return; }
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
        boolean enabled = true;
        if (payload.containsKey("enabled")) {
            try { enabled = Boolean.parseBoolean(String.valueOf(payload.get("enabled"))); } catch (Exception ignored) {}
        }
        if (username == null || username.isEmpty()) { sendJsonError(exchange, 400, "username required"); return; }
        try {
            boolean ok = userService.createUser(username, email, enabled);
            if (!ok) {
                // サービスが false を返した場合、既に存在する可能性があるため念のため確認
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
}
