package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.security.db.DbService;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.service.BlockIpService;
import com.edamame.web.service.FragmentService;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * block_ip管理用コントローラ。閲覧は認証ユーザー、作成/削除はオペレーター以上。
 */
public class BlockIpController implements HttpHandler {

    private final AuthenticationService authService;
    private final BlockIpService service;
    private final FragmentService fragmentService;
    private final ObjectMapper mapper;

    public BlockIpController(AuthenticationService authService) {
        this.authService = authService;
        this.service = new BlockIpService();
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
        if (sessionInfo == null) { sendJsonError(exchange, 401, "Unauthorized - authentication required"); return; }
        String username = sessionInfo.getUsername();
        var userService = new UserServiceImpl();
        boolean isAdmin = false;
        boolean isOperator = false;
        try {
            isAdmin = userService.isAdmin(username);
            isOperator = isAdmin || userService.hasRoleIncludingHigher(username, "operator");
        } catch (Exception ignored) {}

        applySecurityHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(method)) { exchange.sendResponseHeaders(200, 0); exchange.getResponseBody().close(); return; }

        try {
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/fragment/block_ip")) {
                handleFragment(exchange, username, isOperator);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/block-ip")) {
                handleList(exchange, isOperator);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/block-ip/cleanup-status")) {
                handleCleanupStatus(exchange);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/block-ip/agents")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleAgentList(exchange);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && normalized.equals("/api/block-ip")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleCreate(exchange, username);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && normalized.matches("/api/block-ip/\\d+/revoke")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleRevoke(exchange, username);
                return;
            }
            if ("POST".equalsIgnoreCase(method) && normalized.matches("/api/block-ip/\\d+/activate")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleActivate(exchange, username);
                return;
            }
            if ("PUT".equalsIgnoreCase(method) && normalized.matches("/api/block-ip/\\d+")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleUpdate(exchange, username);
                return;
            }
            if ("DELETE".equalsIgnoreCase(method) && normalized.matches("/api/block-ip/\\d+")) {
                if (!isOperator) { sendJsonError(exchange, 403, "Forbidden - operator role required"); return; }
                handleDelete(exchange);
                return;
            }
            sendJsonError(exchange, 404, "Not Found");
        } catch (Exception e) {
            AppLogger.error("BlockIpController error: " + e.getMessage());
            sendJsonError(exchange, 500, "server error");
        }
    }

    private void handleFragment(HttpExchange exchange, String username, boolean canOperate) throws IOException {
        String tpl = fragmentService.getFragmentTemplate("block_ip");
        if (tpl == null) {
            sendHtml(exchange, 200, "<div class=\"card\"><p>テンプレートが見つかりません</p></div>");
            return;
        }
        String wrapped = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"block_ip\">" + tpl +
                "<div id=\"block-ip-meta\" data-can-operate=\"" + (canOperate ? "true" : "false") + "\" data-current-user=\"" + WebSecurityUtils.escapeHtml(username) + "\" style=\"display:none;\"></div></div>";
        sendHtml(exchange, 200, wrapped);
    }

    private void handleList(HttpExchange exchange, boolean canOperate) throws IOException {
        var params = WebSecurityUtils.parseQueryParams(exchange.getRequestURI().getQuery());
        String status = params.get("status");
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        String serviceType = params.getOrDefault("serviceType", "all");
        String q = params.getOrDefault("q", "");
        String sort = params.getOrDefault("sort", "ipAddress");
        String order = params.getOrDefault("order", "asc");
        int page = parseIntOr(params.get("page"), 1);
        int size = parseIntOr(params.get("size"), 20);

        var result = service.search(status, serviceType, q, sort, order, page, size);
        Map<String, Object> resp = new HashMap<>();
        resp.put("items", result.items());
        resp.put("total", result.total());
        resp.put("totalPages", result.totalPages());
        resp.put("page", result.page());
        resp.put("size", result.size());
        resp.put("canOperate", canOperate);
        sendJson(exchange, 200, resp);
    }

    private void handleCleanupStatus(HttpExchange exchange) throws IOException {
        Map<String, Object> resp = Map.of(
                "version", DbService.getBlockIpCleanupVersion()
        );
        sendJson(exchange, 200, resp);
    }

    private void handleAgentList(HttpExchange exchange) throws IOException {
        try {
            var names = service.listAgentNames();
            sendJson(exchange, 200, Map.of("items", names));
        } catch (Exception e) {
            AppLogger.error("block_ipエージェント候補取得エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to list agent names");
        }
    }

    private void handleCreate(HttpExchange exchange, String username) throws IOException {
        Map<String, Object> payload = readJson(exchange);
         String ip = toStringSafe(payload.get("ipAddress"));
         String reason = toStringSafe(payload.get("reason"));
         String target = toStringSafe(payload.get("targetAgentName"));
         String endAtStr = toStringSafe(payload.get("endAt"));
         if (ip.isBlank()) { sendJsonError(exchange, 400, "ipAddress required"); return; }
         if (reason.isBlank()) { sendJsonError(exchange, 400, "reason required"); return; }
         if (!isValidTargetAgent(target)) { sendJsonError(exchange, 400, "invalid targetAgentName"); return; }
         LocalDateTime endAt = parseEndAt(endAtStr);
        if (endAt != null && endAt.isBefore(LocalDateTime.now())) { sendJsonError(exchange, 400, "endAt must be future or empty"); return; }
        try {
            int inserted = service.createManual(ip, target, reason, endAt, username);
            AppLogger.info("block_ipを手動作成しました: " + ip + " by " + username);
            sendJson(exchange, 200, Map.of("inserted", inserted));
        } catch (Exception e) {
            AppLogger.error("block_ip手動作成エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to create");
        }
    }

    private void handleRevoke(HttpExchange exchange, String username) throws IOException {
        long id = parseIdFromRevoke(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        try {
            int updated = service.revoke(id, username);
            if (updated == 0) { sendJsonError(exchange, 404, "not found or already revoked"); return; }
            AppLogger.info("block_ipを無効化しました: id=" + id + " by " + username);
            sendJson(exchange, 200, Map.of("updated", updated));
        } catch (Exception e) {
            AppLogger.error("block_ip無効化エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to revoke");
        }
    }

    private void handleActivate(HttpExchange exchange, String username) throws IOException {
        long id = parseIdFromAction(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        Map<String, Object> payload = readJson(exchange);
        boolean hasEndAt = payload.containsKey("endAt");
        LocalDateTime newEndAt = hasEndAt ? parseEndAt(toStringSafe(payload.get("endAt"))) : null;
        try {
            int updated = service.activate(id, username, hasEndAt, newEndAt);
            if (updated == 0) { sendJsonError(exchange, 404, "not found"); return; }
            AppLogger.info("block_ipを有効化しました: id=" + id + " by " + username);
            sendJson(exchange, 200, Map.of("updated", updated));
        } catch (Exception e) {
            AppLogger.error("block_ip有効化エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to activate");
        }
    }

    private void handleUpdate(HttpExchange exchange, String username) throws IOException {
        long id = parseId(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        Map<String, Object> payload = readJson(exchange);
         String reason = toStringSafe(payload.get("reason"));
         String target = toStringSafe(payload.get("targetAgentName"));
         String endAtStr = toStringSafe(payload.get("endAt"));
         if (reason.isBlank()) { sendJsonError(exchange, 400, "reason required"); return; }
         if (!isValidTargetAgent(target)) { sendJsonError(exchange, 400, "invalid targetAgentName"); return; }
         LocalDateTime endAt = parseEndAt(endAtStr);
        if (endAt != null && endAt.isBefore(LocalDateTime.now())) { sendJsonError(exchange, 400, "endAt must be future or empty"); return; }
        try {
            int updated = service.update(id, target, reason, endAt, username);
            if (updated == 0) { sendJsonError(exchange, 404, "not found"); return; }
            AppLogger.info("block_ipを更新しました: id=" + id + " by " + username);
            sendJson(exchange, 200, Map.of("updated", updated));
        } catch (Exception e) {
            AppLogger.error("block_ip更新エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to update");
        }
    }

    private void handleDelete(HttpExchange exchange) throws IOException {
        long id = parseId(exchange.getRequestURI().getPath());
        if (id <= 0) { sendJsonError(exchange, 400, "invalid id"); return; }
        try {
            int deleted = service.delete(id);
            if (deleted == 0) { sendJsonError(exchange, 404, "not found"); return; }
            AppLogger.info("block_ipを削除しました: id=" + id);
            sendJson(exchange, 200, Map.of("deleted", deleted));
        } catch (Exception e) {
            AppLogger.error("block_ip削除エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to delete");
        }
    }

    private LocalDateTime parseEndAt(String endAtStr) {
        if (endAtStr == null || endAtStr.isBlank()) return null;
        try { return LocalDateTime.parse(endAtStr.trim()); }
        catch (Exception e) { return null; }
    }

    private int parseIntOr(String v, int def) {
        try { return Integer.parseInt(v); } catch (Exception e) { return def; }
    }

    private long parseId(String path) {
        try {
            String[] seg = path.split("/");
            return Long.parseLong(seg[seg.length - 1]);
        } catch (Exception e) { return -1L; }
    }

    private long parseIdFromRevoke(String path) {
        try {
            String[] seg = path.split("/");
            if (seg.length < 2) { return -1L; }
            return Long.parseLong(seg[seg.length - 2]);
        } catch (Exception e) { return -1L; }
    }

    private long parseIdFromAction(String path) {
        try {
            String[] seg = path.split("/");
            if (seg.length < 2) { return -1L; }
            return Long.parseLong(seg[seg.length - 2]);
        } catch (Exception e) { return -1L; }
    }

    private String toStringSafe(Object v) { return v == null ? "" : v.toString(); }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        try { return mapper.readValue(body, new TypeReference<>(){}); }
        catch (Exception e) { return Map.of(); }
    }

    private void sendJson(HttpExchange exchange, int status, Map<String, Object> data) throws IOException {
        String json = mapper.writeValueAsString(data);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendHtml(HttpExchange exchange, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private void applySecurityHeaders(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
    }

    private boolean isValidTargetAgent(String val) {
        if (val == null || val.isBlank()) {
            return true; // 任意項目
        }
        if (val.length() > 64) {
            return false;
        }
        return TARGET_AGENT_PATTERN.matcher(val).matches();
    }

    private static final Pattern TARGET_AGENT_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");
}

