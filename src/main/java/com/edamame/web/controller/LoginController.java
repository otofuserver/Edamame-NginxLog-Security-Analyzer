package com.edamame.web.controller;

import com.edamame.web.config.WebConstants;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.security.WebSecurityUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import com.edamame.security.tools.AppLogger;

/**
 * ログイン認証コントローラー
 * ログイン画面の表示とログイン処理を担当
 */
public class LoginController implements HttpHandler {

    private final AuthenticationService authService;

    /**
     * コンストラクタ
     * @param authService 認証サービス
     */
    public LoginController(AuthenticationService authService) {
        this.authService = authService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            switch (method) {
                case "GET" -> handleLoginPage(exchange);
                case "POST" -> handleLoginSubmit(exchange);
                default -> sendMethodNotAllowed(exchange);
            }
        } catch (Exception e) {
            AppLogger.error("LoginController処理エラー: " + e.getMessage());
            sendInternalServerError(exchange);
        }
    }

    /**
     * ログイン画面を表示
     */
    private void handleLoginPage(HttpExchange exchange) throws IOException {
        // 既にログイン済みかチェック
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId != null && authService.validateSession(sessionId) != null) {
            sendDashboardRedirect(exchange);
            return;
        }

        // URLクエリパラメータを解析
        String query = exchange.getRequestURI().getQuery();
        boolean showLogoutSuccess = query != null && query.contains("logout=success");

        String loginHtml = generateLoginHtml(showLogoutSuccess);
        sendHtmlResponse(exchange, loginHtml);
    }

    /**
     * ログイン処理
     */
    private void handleLoginSubmit(HttpExchange exchange) throws IOException {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormData(requestBody);

        String username = params.get("username");
        String password = params.get("password");
        boolean rememberMe = "on".equals(params.get("rememberMe"));

        // XSS攻撃チェック
        if (WebSecurityUtils.detectXSS(username) || WebSecurityUtils.detectXSS(password)) {
            AppLogger.warn("XSS攻撃検知 - ユーザー: " + username);
            sendErrorResponse(exchange, 400, "Invalid input detected");
            return;
        }

        // IPアドレスとUser-Agentを取得
        String ipAddress = exchange.getRemoteAddress() != null ? exchange.getRemoteAddress().getAddress().getHostAddress() : "";
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        // 認証を実行（IP・UA付き）
        String sessionId = authService.authenticate(username, password, rememberMe, ipAddress, userAgent);

        if (sessionId != null) {
            // 認証成功 - セッションCookieを設定
            setSessionCookie(exchange, sessionId, rememberMe);
            sendLoginSuccessResponse(exchange);
        } else {
            // 認証失敗 - エラーメッセージ付きでログイン画面を再表示
            String loginHtml = generateLoginHtml("ユーザー名またはパスワードが正しくありません。");
            sendHtmlResponse(exchange, loginHtml);
        }
    }

    /**
     * ログイン画面のHTMLを生成（ログアウト成功メッセージ対応）
     */
    private String generateLoginHtml(boolean showLogoutSuccess) {
        String successMessage = showLogoutSuccess ? "ログアウトしました。" : null;
        return generateLoginHtml(successMessage);
    }

    /**
     * ログイン画面のHTMLを生成
     */
    private String generateLoginHtml(String message) {
        StringBuilder html = new StringBuilder();

        html.append("""
            <!DOCTYPE html>
            <html lang="ja">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Edamame Security Analyzer - ログイン</title>
                <style>
                    * {
                        margin: 0;
                        padding: 0;
                        box-sizing: border-box;
                    }
                    body {
                        font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        height: 100vh;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                    }
                    .login-container {
                        background: white;
                        padding: 2rem;
                        border-radius: 10px;
                        box-shadow: 0 10px 25px rgba(0,0,0,0.1);
                        width: 100%;
                        max-width: 400px;
                    }
                    .logo {
                        text-align: center;
                        margin-bottom: 2rem;
                    }
                    .logo h1 {
                        color: #333;
                        font-size: 1.8rem;
                        margin-bottom: 0.5rem;
                    }
                    .logo p {
                        color: #666;
                        font-size: 0.9rem;
                    }
                    .form-group {
                        margin-bottom: 1rem;
                    }
                    .form-group label {
                        display: block;
                        margin-bottom: 0.5rem;
                        color: #333;
                        font-weight: 500;
                    }
                    .form-group input[type="text"],
                    .form-group input[type="password"] {
                        width: 100%;
                        padding: 0.75rem;
                        border: 2px solid #e1e5e9;
                        border-radius: 5px;
                        font-size: 1rem;
                        transition: border-color 0.3s;
                    }
                    .form-group input[type="text"]:focus,
                    .form-group input[type="password"]:focus {
                        outline: none;
                        border-color: #667eea;
                    }
                    .remember-me {
                        display: flex;
                        align-items: center;
                        margin-bottom: 1.5rem;
                    }
                    .remember-me input[type="checkbox"] {
                        margin-right: 0.5rem;
                    }
                    .remember-me label {
                        color: #666;
                        font-size: 0.9rem;
                        cursor: pointer;
                    }
                    .login-btn {
                        width: 100%;
                        padding: 0.75rem;
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        color: white;
                        border: none;
                        border-radius: 5px;
                        font-size: 1rem;
                        font-weight: 500;
                        cursor: pointer;
                        transition: transform 0.2s;
                    }
                    .login-btn:hover {
                        transform: translateY(-2px);
                    }
                    .message {
                        padding: 0.75rem;
                        border-radius: 5px;
                        margin-bottom: 1rem;
                        border-left: 4px solid;
                    }
                    .error-message {
                        background: #fee;
                        color: #c33;
                        border-left-color: #c33;
                    }
                    .success-message {
                        background: #efe;
                        color: #3c3;
                        border-left-color: #3c3;
                    }
                    .footer {
                        text-align: center;
                        margin-top: 2rem;
                        color: #666;
                        font-size: 0.8rem;
                    }
                </style>
            </head>
            <body>
                <div class="login-container">
                    <div class="logo">
                        <h1>🌱 Edamame</h1>
                        <p>Security Analyzer Dashboard</p>
                    </div>
            """);

        // メッセージがある場合は表示
        if (message != null && !message.trim().isEmpty()) {
            String messageClass = message.contains("ログアウト") ? "success-message" : "error-message";
            html.append("<div class=\"message ").append(messageClass).append("\">")
                .append(WebSecurityUtils.escapeHtml(message))
                .append("</div>");
        }

        html.append("""
                    <form method="POST" action="/login">
                        <div class="form-group">
                            <label for="username">ユーザー名</label>
                            <input type="text" id="username" name="username" required
                                   placeholder="ユーザー名を入力" autocomplete="username">
                        </div>
                        <div class="form-group">
                            <label for="password">パスワード</label>
                            <input type="password" id="password" name="password" required
                                   placeholder="パスワードを入力" autocomplete="current-password">
                        </div>
                        <div class="remember-me">
                            <input type="checkbox" id="rememberMe" name="rememberMe">
                            <label for="rememberMe">ログインしたままにする（30日間）</label>
                        </div>
                        <button type="submit" class="login-btn">ログイン</button>
                    </form>
                    <div class="footer">
                        <p>デフォルトユーザー: admin / admin123</p>
                        <p>&copy; 2025 Edamame Security Analyzer</p>
                    </div>
                </div>
                <script>
                    document.addEventListener('DOMContentLoaded', function() {
                        document.getElementById('username').focus();
                    });
                    document.addEventListener('keypress', function(e) {
                        if (e.key === 'Enter') {
                            document.querySelector('form').submit();
                        }
                    });
                </script>
            </body>
            </html>
            """);

        return html.toString();
    }

    /**
     * CookieからセッションIDを取得
     */
    private String getSessionIdFromCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        return WebConstants.extractSessionId(cookieHeader);
    }

    /**
     * フォームデータをパース
     */
    private Map<String, String> parseFormData(String formData) {
        Map<String, String> params = new HashMap<>();

        if (formData == null || formData.trim().isEmpty()) {
            return params;
        }

        String[] pairs = formData.split("&");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                try {
                    String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                    params.put(key, value);
                } catch (Exception e) {
                    AppLogger.warn("フォームデータパース失敗: " + pair + " - " + e.getMessage());
                }
            }
        }

        return params;
    }

    /**
     * セッションCookieを設定
     */
    private void setSessionCookie(HttpExchange exchange, String sessionId, boolean rememberMe) {
        String cookieValue = WebConstants.createSessionCookieValue(sessionId, rememberMe);
        exchange.getResponseHeaders().set("Set-Cookie", cookieValue);
    }

    /**
     * HTMLレスポンスを送信
     */
    private void sendHtmlResponse(HttpExchange exchange, String html) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, html.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(html.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * ログイン成功レスポンスを送信（302リダイレクト）
     */
    private void sendLoginSuccessResponse(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/main");
        exchange.sendResponseHeaders(302, -1);
    }

    /**
     * ダッシュボードにリダイレクト
     */
    private void sendDashboardRedirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/main");
        exchange.sendResponseHeaders(302, -1);
    }

    /**
     * エラーレスポンスを送信
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        exchange.sendResponseHeaders(statusCode, message.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(message.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Method Not Allowedレスポンスを送信
     */
    private void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendErrorResponse(exchange, 405, "Method Not Allowed");
    }

    /**
     * 内部サーバーエラーを送信
     */
    private void sendInternalServerError(HttpExchange exchange) throws IOException {
        String errorMessage = "Internal Server Error";
        exchange.sendResponseHeaders(500, errorMessage.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(errorMessage.getBytes(StandardCharsets.UTF_8));
        }
    }
}
