package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.service.FragmentService;
import com.edamame.web.service.WhitelistSettingsService;
import com.edamame.web.service.WhitelistSettingsService.WhitelistUpdateResult;
import com.edamame.security.action.MailActionHandler;
import com.edamame.security.NginxLogToMysql;
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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ホワイトリスト設定画面およびAPIを提供するコントローラ。
 * 閲覧・更新は管理者のみ許可。
 */
public class WhitelistSettingsController implements HttpHandler {

    private final AuthenticationService authService;
    private final WhitelistSettingsService service;
    private final FragmentService fragmentService;
    private final ObjectMapper mapper;

    public WhitelistSettingsController(AuthenticationService authService) {
        this.authService = authService;
        this.service = new WhitelistSettingsService();
        this.fragmentService = new FragmentService();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String normalized = path == null ? "/" : path.replaceAll("/+$", "");
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String sessionId = com.edamame.web.config.WebConstants.extractSessionId(cookie);
        var sessionInfo = sessionId == null ? null : authService.validateSession(sessionId);
        if (sessionInfo == null) {
            sendJsonError(exchange, 401, "Unauthorized - authentication required");
            return;
        }
        String username = sessionInfo.getUsername();
        boolean isAdmin = false;
        try {
            isAdmin = new com.edamame.web.service.impl.UserServiceImpl().isAdmin(username);
        } catch (Exception ignored) {}

        applySecurityHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
            return;
        }

        try {
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/fragment/whitelist_settings")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleFragment(exchange);
                return;
            }
            if ("GET".equalsIgnoreCase(method) && normalized.equals("/api/whitelist-settings")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleGetSettings(exchange);
                return;
            }
            if ("PUT".equalsIgnoreCase(method) && normalized.equals("/api/whitelist-settings")) {
                if (!isAdmin) { sendJsonError(exchange, 403, "Forbidden - admin role required"); return; }
                handleUpdate(exchange, username);
                return;
            }
            sendJsonError(exchange, 404, "Not Found");
        } catch (Exception e) {
            AppLogger.error("WhitelistSettingsController error: " + e.getMessage());
            sendJsonError(exchange, 500, "server error");
        }
    }

    private void handleFragment(HttpExchange exchange) throws IOException {
        String tpl = fragmentService.getFragmentTemplate("whitelist_settings");
        if (tpl == null) {
            sendHtml(exchange, 200, "<div class=\"card\"><p>テンプレートが見つかりません</p></div>");
            return;
        }
        String wrapped = "<div class=\"fragment-root\" data-auto-refresh=\"0\" data-fragment-name=\"whitelist_settings\">" + tpl + "</div>";
        sendHtml(exchange, 200, wrapped);
    }

    private void handleGetSettings(HttpExchange exchange) throws IOException {
        try {
            var settings = service.load();
            Map<String, Object> resp = new HashMap<>();
            resp.put("whitelistMode", settings.whitelistMode());
            resp.put("whitelistIps", settings.whitelistIps());
            sendJson(exchange, 200, resp);
        } catch (IllegalArgumentException e) {
            sendJsonError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            AppLogger.error("ホワイトリスト設定取得エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to load settings");
        }
    }

    private void handleUpdate(HttpExchange exchange, String username) throws IOException {
        Map<String, Object> payload = readJson(exchange);
        boolean mode = Boolean.parseBoolean(payload.getOrDefault("whitelistMode", Boolean.FALSE).toString());
        Object ipsObj = payload.get("whitelistIps");
        List<String> ips;
        if (ipsObj instanceof List<?> list) {
            ips = list.stream().map(String::valueOf).toList();
        } else if (ipsObj == null) {
            ips = List.of();
        } else {
            ips = List.of(String.valueOf(ipsObj));
        }
        try {
            WhitelistUpdateResult result = service.update(mode, ips, username);
            Map<String, Object> resp = new HashMap<>();
            resp.put("whitelistMode", result.after().whitelistMode());
            resp.put("whitelistIps", result.after().whitelistIps());
            sendJson(exchange, 200, resp);
            sendWhitelistAuditMail(result, username);
        } catch (IllegalArgumentException e) {
            sendJsonError(exchange, 400, e.getMessage());
        } catch (Exception e) {
            AppLogger.error("ホワイトリスト設定更新エラー: " + e.getMessage());
            sendJsonError(exchange, 500, "failed to update settings");
        }
    }

    /**
     * 監査ロールと上位ロールにホワイトリスト変更通知メールを送信する。
     * 差分が無い場合は送信しない。
     * @param result 更新差分
     * @param username 操作者
     */
    private void sendWhitelistAuditMail(WhitelistUpdateResult result, String username) {
        boolean hasDiff = result.modeChanged() || !result.addedIps().isEmpty() || !result.removedIps().isEmpty();
        if (!hasDiff) {
            AppLogger.debug("ホワイトリスト変更なしのため監査メール送信をスキップ");
            return;
        }

        try {
            MailActionHandler mailer = NginxLogToMysql.getSharedMailHandler();
            if (mailer == null) mailer = new MailActionHandler();

            String fromEmail = "noreply@example.com";
            String fromName = "Edamame Security Analyzer";

            String modeLabel = result.modeChanged()
                ? (result.before().whitelistMode() ? "ON→OFF" : "OFF→ON")
                : (result.after().whitelistMode() ? "ON" : "OFF");

            String subject = "[Edamame] ホワイトリスト変更: " + modeLabel;

            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            StringBuilder body = new StringBuilder();
            body.append("ホワイトリスト設定が更新されました\n");
            body.append("操作ユーザー: ").append(username).append('\n');
            body.append("実行時刻: ").append(LocalDateTime.now().format(fmt)).append("\n\n");
            body.append("モード: ").append(modeLabel).append("\n");
            body.append("追加IP: ").append(result.addedIps().isEmpty() ? "なし" : String.join(", ", result.addedIps())).append("\n");
            body.append("削除IP: ").append(result.removedIps().isEmpty() ? "なし" : String.join(", ", result.removedIps())).append("\n\n");
            body.append("更新後の許可IP一覧:\n");
            if (result.after().whitelistIps().isEmpty()) {
                body.append("(なし)\n");
            } else {
                result.after().whitelistIps().forEach(ip -> body.append("- ").append(ip).append('\n'));
            }

            String res = mailer.sendToRoleIncludingHigherRoles("auditor", fromEmail, fromName, subject, body.toString());
            AppLogger.info("ホワイトリスト監査メール送信結果: " + res);
        } catch (Exception e) {
            AppLogger.warn("ホワイトリスト監査メール送信で例外: " + e.getMessage());
        }
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return Map.of();
        try {
            return mapper.readValue(body, new TypeReference<>(){});
        } catch (Exception e) {
            throw new IOException("invalid json", e);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        String json = mapper.writeValueAsString(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendHtml(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private void applySecurityHeaders(HttpExchange exchange) {
        Map<String, String> headers = WebSecurityUtils.getSecurityHeaders();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            exchange.getResponseHeaders().set(h.getKey(), h.getValue());
        }
    }
}
