package com.edamame.web.controller;

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
import java.util.function.BiConsumer;

/**
 * ログイン認証コントローラー
 * ログイン画面の表示とログイン処理を担当
 */
public class LoginController implements HttpHandler {

    private final AuthenticationService authService;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param authService 認証サービス
     * @param logFunction ログ出力関数
     */
    public LoginController(AuthenticationService authService, BiConsumer<String, String> logFunction) {
        this.authService = authService;
        this.logFunction = logFunction;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();

        try {
            switch (method) {
                case "GET" -> handleLoginPage(exchange);
                case "POST" -> handleLoginSubmit(exchange);
                default -> sendMethodNotAllowed(exchange);
            }
        } catch (Exception e) {
            logFunction.accept("LoginController でエラー: " + e.getMessage(), "ERROR");
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * ログイン画面を表示
     */
    private void handleLoginPage(HttpExchange exchange) throws IOException {
        // 既にログイン済みかチェック
        String sessionId = getSessionIdFromCookie(exchange);
        if (sessionId != null && authService.validateSession(sessionId) != null) {
            // 既にログイン済みの場合はダッシュボードにリダイレクト
            sendRedirect(exchange, "/dashboard");
            return;
        }

        String loginHtml = generateLoginHtml();

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, loginHtml.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(loginHtml.getBytes(StandardCharsets.UTF_8));
        }
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
            logFunction.accept("ログイン試行でXSS攻撃を検知: " + username, "SECURITY");
            sendErrorResponse(exchange, 400, "Invalid input detected");
            return;
        }

        // 認証を実行
        String sessionId = authService.authenticate(username, password, rememberMe);

        if (sessionId != null) {
            // 認証成功 - セッションCookieを設定してダッシュボードにリダイレクト
            String cookieValue = "sessionId=" + sessionId + "; Path=/; HttpOnly; SameSite=Strict";
            if (rememberMe) {
                cookieValue += "; Max-Age=" + (30 * 24 * 60 * 60); // 30日
            }

            exchange.getResponseHeaders().set("Set-Cookie", cookieValue);
            sendRedirect(exchange, "/dashboard");
        } else {
            // 認証失敗 - エラーメッセージ付きでログイン画面を再表示
            String loginHtml = generateLoginHtml("ユーザー名またはパスワードが正しくありません。");

            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, loginHtml.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(loginHtml.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * ログイン画面のHTMLを生成
     */
    private String generateLoginHtml() {
        return generateLoginHtml(null);
    }

    /**
     * ログイン画面のHTMLを生成（エラーメッセージ付き）
     */
    private String generateLoginHtml(String errorMessage) {
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
                    
                    .error-message {
                        background: #fee;
                        color: #c33;
                        padding: 0.75rem;
                        border-radius: 5px;
                        margin-bottom: 1rem;
                        border-left: 4px solid #c33;
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

        // エラーメッセージがある場合は表示
        if (errorMessage != null && !errorMessage.trim().isEmpty()) {
            html.append("<div class=\"error-message\">")
                .append(WebSecurityUtils.escapeHtml(errorMessage))
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
                    // フォーカス処理
                    document.addEventListener('DOMContentLoaded', function() {
                        document.getElementById('username').focus();
                    });
                    
                    // Enterキーでフォーム送信
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
        if (cookieHeader == null) {
            return null;
        }

        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] parts = cookie.trim().split("=", 2);
            if (parts.length == 2 && "sessionId".equals(parts[0])) {
                return parts[1];
            }
        }
        return null;
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
                    logFunction.accept("フォームデータのパースに失敗: " + pair, "WARN");
                }
            }
        }

        return params;
    }

    /**
     * リダイレクトレスポンスを送信
     */
    private void sendRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
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
}
