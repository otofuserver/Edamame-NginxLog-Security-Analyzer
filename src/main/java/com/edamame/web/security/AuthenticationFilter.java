package com.edamame.web.security;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import com.edamame.security.tools.AppLogger;
import com.edamame.web.service.UserService;
import com.edamame.web.service.impl.UserServiceImpl;
import com.edamame.web.config.WebConstants;

/**
 * HTTP認証フィルター
 * セッション管理とリダイレクト処理を担当
 */
public class AuthenticationFilter implements HttpHandler {

    private final AuthenticationService authService;
    private final HttpHandler protectedHandler;

    /**
     * コンストラクタ
     * @param authService 認証サービス
     * @param protectedHandler 保護されたハンドラ
     */
    public AuthenticationFilter(AuthenticationService authService, HttpHandler protectedHandler) {
        this.authService = authService;
        this.protectedHandler = protectedHandler;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        try {
            // セッションチェック
            String sessionId = getSessionIdFromCookie(exchange);
            if (sessionId != null) {
                AuthenticationService.SessionInfo sessionInfo = authService.validateSession(sessionId);
                if (sessionInfo != null) {
                    // セッション有効
                    exchange.setAttribute(WebConstants.REQUEST_ATTR_USERNAME, sessionInfo.getUsername());
                    AppLogger.debug("認証済みユーザー: " + sessionInfo.getUsername() + " (セッションID: " + sessionId + ")");
                    // isAdmin を一度だけ判定してリクエスト属性に保存（コントローラで再判定を避けるため）
                    try {
                        UserService us = new UserServiceImpl();
                        boolean isAdmin = us.isAdmin(sessionInfo.getUsername());
                        exchange.setAttribute(WebConstants.REQUEST_ATTR_IS_ADMIN, isAdmin);
                        // nonce をリクエスト単位で生成して共有し、CSP をレスポンスヘッダで送信する
                        String scriptNonce = generateNonce();
                        exchange.setAttribute(WebConstants.REQUEST_ATTR_SCRIPT_NONCE, scriptNonce);
                        String csp = "default-src 'self'; script-src 'self' 'nonce-" + scriptNonce + "'; style-src 'self' 'unsafe-inline'";
                        exchange.getResponseHeaders().set("Content-Security-Policy", csp);
                    } catch (Exception e) {
                        AppLogger.warn("isAdmin 判定失敗: " + e.getMessage());
                        exchange.setAttribute(WebConstants.REQUEST_ATTR_IS_ADMIN, false);
                    }
                    protectedHandler.handle(exchange);
                    return;
                } else {
                    AppLogger.warn("無効なセッションID: " + sessionId + "。ログイン画面へリダイレクト");
                }
            } else {
                AppLogger.debug("未認証アクセス: " + path + "。ログイン画面へリダイレクト");
            }
            // 未認証または無効セッションの場合はログイン画面へリダイレクト
            exchange.getResponseHeaders().set("Location", "/login");
            exchange.sendResponseHeaders(302, -1);
        } catch (Exception e) {
            AppLogger.error("AuthenticationFilter処理エラー: " + e.getMessage());
            sendInternalServerError(exchange);
        }
    }

    /**
     * CookieからセッションIDを取得
     */
    private String getSessionIdFromCookie(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        return com.edamame.web.config.WebConstants.extractSessionId(cookieHeader);
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

    /**
     * リクエストスコープのnonceを生成
     */
    private String generateNonce() {
        var random = new java.security.SecureRandom();
        byte[] nonceBytes = new byte[16];
        random.nextBytes(nonceBytes);
        return java.util.Base64.getEncoder().encodeToString(nonceBytes);
    }
}
