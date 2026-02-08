package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.config.WebConstants;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.security.WebSecurityUtils;
import com.edamame.web.service.UserService;
import com.edamame.web.service.impl.UserServiceImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 初回ログイン時にパスワード変更を強制するコントローラー
 * 認証済みユーザーに対してのみ表示し、変更後は必須フラグを解除する
 */
public class ForcePasswordChangeController implements HttpHandler {

    private final AuthenticationService authService;
    private final UserService userService;

    /**
     * コンストラクタ
     * @param authService 認��サービス
     */
    public ForcePasswordChangeController(AuthenticationService authService) {
        this.authService = authService;
        this.userService = new UserServiceImpl();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        try {
            String sessionId = WebConstants.extractSessionId(exchange.getRequestHeaders().getFirst("Cookie"));
            AuthenticationService.SessionInfo sessionInfo = authService.validateSession(sessionId);
            if (sessionInfo == null) {
                redirect(exchange, "/login");
                return;
            }

            if ("GET".equalsIgnoreCase(method)) {
                renderPage(exchange, null);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePost(exchange, sessionInfo.getUsername());
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (Exception e) {
            AppLogger.error("ForcePasswordChangeController エラー: " + e.getMessage());
            sendError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * パスワード変更POSTを処理
     * @param exchange HTTPエクスチェンジ
     * @param username 対象ユーザー名
     * @throws IOException IO例外
     */
    private void handlePost(HttpExchange exchange, String username) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = parseForm(body);
        String currentPw = form.getOrDefault("currentPassword", "").trim();
        String newPw = form.getOrDefault("newPassword", "").trim();
        String confirm = form.getOrDefault("confirmPassword", "").trim();

        if (currentPw.isEmpty() || newPw.isEmpty() || confirm.isEmpty()) {
            renderPage(exchange, "すべての項目を入力してください。");
            return;
        }
        if (!newPw.equals(confirm)) {
            renderPage(exchange, "新しいパスワードと確認が一致しません。");
            return;
        }
        if (!WebSecurityUtils.isPasswordValid(newPw)) {
            renderPage(exchange, "パスワードポリシーに違反しています（" + WebSecurityUtils.PASSWORD_POLICY_MESSAGE + "）。");
            return;
        }
        if (!authService.verifyPassword(username, currentPw)) {
            renderPage(exchange, "現在のパスワードが正しくありません。");
            return;
        }
        if (newPw.equals(currentPw)) {
            renderPage(exchange, "現在のパスワードと同じ値は使用できません。");
            return;
        }

        boolean changed = userService.resetPassword(username, newPw, false);
        if (!changed) {
            renderPage(exchange, "パスワード変更に失敗しました。時間をおいて再試行してください。");
            return;
        }

        AppLogger.info("初回パスワード変更完了: " + username + " at " + LocalDateTime.now());
        redirect(exchange, "/main?view=main");
    }

    /**
     * パスワードポリシー検証
     */
    private boolean isValidPassword(String pw) {
        return WebSecurityUtils.isPasswordValid(pw);
    }

    private void renderPage(HttpExchange exchange, String message) throws IOException {
        String html = buildHtml(message);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String buildHtml(String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            <!DOCTYPE html>
            <html lang=\"ja\">
            <head>
                <meta charset=\"UTF-8\">
                <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                <title>パスワード変更 | Edamame Security Analyzer</title>
                <style>
                    body { font-family: 'Segoe UI', sans-serif; background: #f5f7fb; margin: 0; padding: 0; }
                    .container { max-width: 520px; margin: 40px auto; background: #fff; padding: 28px; border-radius: 10px; box-shadow: 0 10px 25px rgba(0,0,0,0.08); }
                    h1 { margin-bottom: 8px; }
                    p.desc { color: #555; margin-top: 0; margin-bottom: 16px; }
                    .form-group { margin-bottom: 14px; }
                    label { display: block; margin-bottom: 6px; color: #333; font-weight: 600; }
                    input[type=password] { width: 100%; padding: 10px; border: 1px solid #dfe3eb; border-radius: 6px; font-size: 14px; }
                    input[type=password]:focus { outline: none; border-color: #667eea; }
                    .btn { width: 100%; padding: 12px; background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: #fff; border: none; border-radius: 6px; font-size: 15px; cursor: pointer; }
                    .btn:hover { opacity: 0.95; }
                    .message { padding: 12px; border-radius: 6px; margin-bottom: 12px; background: #fee; color: #c33; border-left: 4px solid #c33; }
                </style>
            </head>
            <body>
                <div class=\"container\">
                    <h1>初回パスワード変更</h1>
                    <p class=\"desc\">セキュリティ強化のため、初回ログイン時にパスワード変更が必要です。</p>
        """);
        if (message != null && !message.isBlank()) {
            sb.append("<div class=\"message\">").append(WebSecurityUtils.escapeHtml(message)).append("</div>");
        }
        sb.append("""
                    <form method=\"POST\" action=\"/password/change\">
                        <div class=\"form-group\">
                            <label for=\"currentPassword\">現在のパスワード</label>
                            <input type=\"password\" id=\"currentPassword\" name=\"currentPassword\" required autocomplete=\"current-password\">
                        </div>
                        <div class=\"form-group\">
                            <label for=\"newPassword\">新しいパスワード</label>
                            <input type=\"password\" id=\"newPassword\" name=\"newPassword\" required autocomplete=\"new-password\">
                        </div>
                        <div class=\"form-group\">
                            <label for=\"confirmPassword\">新しいパスワード（確認）</label>
                            <input type=\"password\" id=\"confirmPassword\" name=\"confirmPassword\" required autocomplete=\"new-password\">
                        </div>
                        <button type=\"submit\" class=\"btn\">パスワードを変更する</button>
                    </form>
                </div>
            </body>
            </html>
        """);
        return sb.toString();
    }

    private Map<String, String> parseForm(String body) {
        Map<String, String> map = new HashMap<>();
        if (body == null || body.isBlank()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String val = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                map.put(key, val);
            }
        }
        return map;
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void sendError(HttpExchange exchange, int status, String msg) throws IOException {
        exchange.sendResponseHeaders(status, msg.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg.getBytes(StandardCharsets.UTF_8));
        }
    }
}
