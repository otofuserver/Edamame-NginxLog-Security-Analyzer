package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.service.FragmentService;
import com.edamame.web.service.UrlSuppressionService;
import com.edamame.web.service.impl.UserServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * URL抑止管理用コントローラ。
 * 一覧取得・登録・更新・削除とフラグメント返却を行う。
 */
public class UrlSuppressionController implements HttpHandler {

    private final AuthenticationService authService;
    private final UrlSuppressionService service;
    private final FragmentService fragmentService;
    private final ObjectMapper mapper;

    public UrlSuppressionController(AuthenticationService authService) {
        this.authService = authService;
        this.service = new UrlSuppressionService();
        this.fragmentService = new FragmentService();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String normalized = path == null ? "/" : path.replaceAll("/+$(?<!^)", "");
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookie);
        var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
        if (sessionInfo == null) {
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
        }
        String username = sessionInfo.getUsername();
        var userService = new UserServiceImpl();
        boolean isAdmin = false;
        try { isAdmin = userService.isAdmin(username); } catch (Exception ignored) {}
        boolean isOperator = isAdmin || userService.hasRoleIncludingHigher(username, "operator");

        applySecurityHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            return;
        }

        try {
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/fragment/url_suppressions")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleFragment(exchange, isAdmin, isOperator, username);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && normalized.startsWith("/api/url-suppressions")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleList(exchange, isAdmin);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && normalized.equals("/api/url-suppressions")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleCreate(exchange, username);
                return;
            }
            if ("PUT".equalsIgnoreCase(method) && normalized.matches("/api/url-suppressions/\\d+")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleUpdate(exchange, username);
                return;
            }
            if ("DELETE".equalsIgnoreCase(method) && normalized.matches("/api/url-suppressions/\\d+")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleDelete(exchange);
                return;
            }
            sendJsonError(exchange, 404, "Not Found");
        } catch (Exception e) {
            AppLogger.error("UrlSuppressionController error: " + e.getMessage());
            sendJsonError(exchange, 500, "server error");
        }
    }

    private void handleFragment(HttpExchange exchange, boolean isAdmin, boolean isOperator, String username) throws IOException {
        String tpl = fragmentService.getFragmentTemplate("url_suppression");
        if (tpl == null) {
            sendHtml(exchange, 200, "<div class=\"card\"><p>テンプレートが見つかりません</p></div>");
            return;
        }
        String wrapped = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"url_suppression\">" + tpl + "<div id=\"url-suppression-meta\" data-can-edit=\"" + (isAdmin ? "true" : "false") + "\" data-current-user=\"" + WebSecurityUtils.escapeHtml(username) + "\" style=\"display:none;\"></div></div>";
        sendHtml(exchange, 200, wrapped);
    }

    private void handleList(HttpExchange exchange, boolean isAdmin) throws IOException {
        Map<String, String> params = WebSecurityUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        String q = params.getOrDefault("q", "");
        String server = params.getOrDefault("server", "");
        String sort = params.getOrDefault("sort", "updated_at");
        String order = params.getOrDefault("order", "desc").toLowerCase(Locale.ROOT);
        int page = parseIntOr(params.get("page"), 1);
        int size = parseIntOr(params.get("size"), 20);
        String sanitizedQ = q.isBlank() ? "" : WebSecurityUtils.sanitizeInput(q.trim());
        String sanitizedServer = server.isBlank() ? "" : WebSecurityUtils.sanitizeInput(server.trim());
        String sanitizedSort = WebSecurityUtils.sanitizeInput(sort.trim());
        var result = service.search(sanitizedQ, sanitizedServer, sanitizedSort, order, page, size);
        Map<String, Object> resp = new HashMap<>();
        resp.put("items", sanitize(result.items()));
        resp.put("canEdit", isAdmin);
        resp.put("total", result.total());
        resp.put("totalPages", result.totalPages());
        resp.put("page", result.page());
        resp.put("size", result.size());
        sendJson(exchange, 200, resp);
    }

    private void handleCreate(HttpExchange exchange, String username) throws IOException {
        Map<String, Object> payload = readJson(exchange);
        String serverName = payload.getOrDefault("serverName", "all").toString();
        String pattern = payload.getOrDefault("urlPattern", "").toString();
        String description = payload.getOrDefault("description", "").toString();
        boolean enabled = Boolean.parseBoolean(payload.getOrDefault("isEnabled", Boolean.TRUE).toString());
        if (pattern.isBlank()) { sendJsonError(exchange, 400, "urlPattern required"); return; }
        try {
            int inserted = service.create(serverName, pattern, description, enabled, username);
            AppLogger.info("URL抑止条件を追加しました: pattern=" + pattern + " server=" + serverName + " by=" + username);
            sendJson(exchange, 200, Map.of("inserted", inserted));
        } catch (Exception e) {
            AppLogger.error("URL抑止条件追加エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to create");
        }
    }

    private void handleUpdate(HttpExchange exchange, String username) throws IOException {
        long id = parseId(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        Map<String, Object> payload = readJson(exchange);
        String serverName = payload.getOrDefault("serverName", "all").toString();
        String pattern = payload.getOrDefault("urlPattern", "").toString();
        String description = payload.getOrDefault("description", "").toString();
        boolean enabled = Boolean.parseBoolean(payload.getOrDefault("isEnabled", Boolean.TRUE).toString());
        if (pattern.isBlank()) { sendJsonError(exchange, 400, "urlPattern required"); return; }
        try {
            int updated = service.update(id, serverName, pattern, description, enabled, username);
            if (updated == 0) { sendJsonError(exchange, 404, "not found"); return; }
            AppLogger.info("URL抑止条件を更新しました: id=" + id + " by=" + username);
            sendJson(exchange, 200, Map.of("updated", updated));
        } catch (Exception e) {
            AppLogger.error("URL抑止条件更新エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to update");
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        long id = parseId(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        try {
            int deleted = service.delete(id);
            if (deleted == 0) { sendJsonError(exchange, 404, "not found"); return; }
            AppLogger.info("URL抑止条件を削除しました: id=" + id);
            sendJson(exchange, 200, Map.of("deleted", deleted));
        } catch (Exception e) {
            AppLogger.error("URL抑止条件削除エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to delete");
        }
    }

    private long parseId(String path) {
        try {
            String[] seg = path.split("/");
            return Long.parseLong(seg[seg.length - 1]);
        } catch (Exception e) {
            return -1L;
        }
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        try {
            return mapper.readValue(body, new TypeReference<>(){});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<Map<String, Object>> sanitize(List<Map<String, Object>> list) {
        return list.stream().map(m -> {
            Map<String, Object> n = new HashMap<>();
            m.forEach((k,v) -> {
                if (v instanceof String s) {
                    if ("urlPattern".equals(k)) n.put(k, s); else n.put(k, WebSecurityUtils.sanitizeInput(s));
                } else {
                    n.put(k, v);
                }
            });
            return n;
        }).toList();
    }

    private void sendJson(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendJsonError(HttpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, Map.of("error", message));
    }

    private void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void applySecurityHeaders(HttpExchange exchange) {
        var headers = WebSecurityUtils.getSecurityHeaders();
        headers.forEach((k,v) -> exchange.getResponseHeaders().set(k, v));
    }

    private int parseIntOr(String val, int fallback) {
        try { return Math.max(1, Integer.parseInt(val)); } catch (Exception e) { return fallback; }
    }
}
