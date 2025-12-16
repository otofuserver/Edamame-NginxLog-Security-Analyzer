package com.edamame.web.controller;

import com.edamame.web.config.WebConstants;
import com.edamame.web.security.AuthenticationService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.edamame.security.tools.AppLogger;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * ログアウト処理コントローラー
 * セッション無効化とCookie削除を担当
 */
public class LogoutController implements HttpHandler {

    private final AuthenticationService authService;

    /**
     * コンストラクタ
     * @param authService 認証サービス
     */
    public LogoutController(AuthenticationService authService) {
        this.authService = authService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            handleLogout(exchange);
        } catch (Exception e) {
            AppLogger.error("LogoutController処理エラー: " + e.getMessage());
            sendInternalServerError(exchange);
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
            AuthenticationService.SessionInfo sessionInfo = authService.validateSession(sessionId);
            if (sessionInfo != null) {
                authService.logout(sessionId);
                AppLogger.info("ユーザー「" + sessionInfo.getUsername() + "」がログアウトしました");
            }
        }

        // Cookieを削除（複数パターンに対応）
        clearSessionCookies(exchange);

        // ログイン画面にリダイレクト
        sendLogoutRedirect(exchange);
    }

    /**
     * CookieからセッションIDを取得
     */
    private String getSessionIdFromCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        return WebConstants.extractSessionId(cookieHeader);
    }

    /**
     * セッションCookieを削除
     */
    private void clearSessionCookies(HttpExchange exchange) {
        // メインのセッションCookie削除
        String clearCookie = WebConstants.createClearSessionCookieValue();
        exchange.getResponseHeaders().add("Set-Cookie", clearCookie);

        // 互換性のため、旧Cookie名も削除
        String clearOldCookie = "sessionId=; Path=/; HttpOnly; Max-Age=0";
        exchange.getResponseHeaders().add("Set-Cookie", clearOldCookie);
    }

    /**
     * ログアウト後のリダイレクト
     */
    private void sendLogoutRedirect(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", WebConstants.LOGOUT_SUCCESS_REDIRECT);
        exchange.sendResponseHeaders(302, -1);
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
