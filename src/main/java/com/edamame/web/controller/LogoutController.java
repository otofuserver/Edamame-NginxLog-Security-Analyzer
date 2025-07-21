package com.edamame.web.controller;

import com.edamame.web.security.AuthenticationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * ログアウトコントローラー
 * ログアウト処理とセッション破棄を担当
 */
public class LogoutController implements HttpHandler {

    private final AuthenticationService authService;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param authService 認証サービス
     * @param logFunction ログ出力関数
     */
    public LogoutController(AuthenticationService authService, BiConsumer<String, String> logFunction) {
        this.authService = authService;
        this.logFunction = logFunction;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        try {
            if ("POST".equals(method) || "GET".equals(method)) {
                handleLogout(exchange);
            } else {
                sendMethodNotAllowed(exchange);
            }
        } catch (Exception e) {
            logFunction.accept("LogoutController でエラー: " + e.getMessage(), "ERROR");
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * ログアウト処理
     */
    private void handleLogout(HttpExchange exchange) throws IOException {
        // セッションIDを取得
        String sessionId = getSessionIdFromCookie(exchange);

        if (sessionId != null) {
            // セッションを無効化
            authService.logout(sessionId);
            logFunction.accept("ユーザーがログアウトしました (SessionID: " + sessionId.substring(0, 8) + "...)", "INFO");
        }

        // セッションCookieを削除
        String cookieValue = "sessionId=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
        exchange.getResponseHeaders().set("Set-Cookie", cookieValue);

        // ログイン画面にリダイレクト
        exchange.getResponseHeaders().set("Location", "/login");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(302, -1);
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
