package com.edamame.web.controller;

import com.edamame.security.tools.AppLogger;
import com.edamame.web.service.UserService;
import com.edamame.web.service.impl.UserServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * アクティベーション用のエンドポイント
 */
public class ActivationController implements HttpHandler {

    private final UserService userService;
    private final ObjectMapper objectMapper;

    public ActivationController() {
        this.userService = new UserServiceImpl();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();

            // GET /api/activate?token=...
            if ("GET".equals(method) && (path.equals("/api/activate") || path.equals("/api/activate/"))) {
                String token = null;
                if (query != null && query.startsWith("token=")) token = java.net.URLDecoder.decode(query.substring(6), StandardCharsets.UTF_8);
                if (token == null || token.isEmpty()) {
                    sendJsonResponse(exchange, 400, Map.of("error", "token required"));
                    return;
                }
                boolean ok = userService.activateUserByToken(token);

                // 環境変数からベースURL・サブディレクトリ設定を取得
                String base = System.getenv("WEB_BASE_URL");
                if (base == null || base.isEmpty()) base = "http://localhost:8080";
                // スキーマが含まれていない場合は http を補う
                if (!base.startsWith("http://") && !base.startsWith("https://")) base = "http://" + base;
                // 末尾スラッシュを削除
                if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

                String useSubdirEnv = System.getenv("USE_SUBDIR");
                boolean useSubdir = useSubdirEnv != null && (useSubdirEnv.equalsIgnoreCase("true") || useSubdirEnv.equals("1"));
                String webSubdir = System.getenv("WEB_SUBDIR");
                if (webSubdir == null || webSubdir.isEmpty()) webSubdir = "/sub"; // デフォルト
                // 正規化：先頭スラッシュあり、末尾スラッシュ無し
                if (!webSubdir.startsWith("/")) webSubdir = "/" + webSubdir;
                if (webSubdir.endsWith("/")) webSubdir = webSubdir.substring(0, webSubdir.length() - 1);

                // 3つの表示用URL
                // 表示用URLはサブディレクトリ使用フラグにより決定
                String domainRoot;
                String loginUrl;
                // サブディレクトリ利用時は domainRoot/login 共にサブディレクトリを含めたパスにする
                if (useSubdir) {
                    domainRoot = base + webSubdir + "/";
                    loginUrl = base + webSubdir + "/login";
                } else {
                    domainRoot = base + "/";
                    loginUrl = base + "/login";
                }
                // 常に例示用のサブディレクトリ版も生成（表示用途）
                String subdirLoginUrl = base + webSubdir + "/login";

                // リダイレクト先はサブディレクトリ使用フラグで決定
                String redirectUrl = useSubdir ? subdirLoginUrl : loginUrl;

                // 成功/失敗それぞれシンプルなHTMLページへ返す。ページは20秒後にリダイレクト。
                if (ok) {
                    String html = "<!doctype html>\n" +
                            "<html><head><meta charset=\"utf-8\">" +
                            "<meta http-equiv=\"refresh\" content=\"20;url=" + escapeHtml(redirectUrl) + "\">" +
                            "<title>アカウント有効化完了</title>" +
                            "<style>" +
                            "body{font-family:Segoe UI, Roboto, Arial, Helvetica, sans-serif;background:#f4f6fb;color:#222;margin:0;padding:0;}" +
                            ".container{max-width:760px;margin:80px auto;padding:24px;background:#ffffff;border-radius:8px;box-shadow:0 6px 18px rgba(20,30,50,0.06);}" +
                            "h1{font-size:20px;margin:0 0 8px;color:#0b5ed7;}" +
                            "p{color:#444;margin:8px 0 0;}" +
                            "ul{margin-top:12px;padding-left:20px;color:#333;}" +
                            "a{color:#0b5ed7;text-decoration:none;} a:hover{text-decoration:underline;}" +
                            "</style></head><body>" +
                            "<div class=\"container\">" +
                            "<h1>アカウントをアクティベーションしました</h1>" +
                            "<p>20秒後にログインページへ移動します。すぐに移動する場合はリンクをクリックしてください。</p>" +
                            "<ul>" +
                            "<li>ログインページ: <a href=\"" + escapeHtml(loginUrl) + "\">" + escapeHtml(loginUrl) + "</a></li>" +
                            "</ul>" +
                            "</div></body></html>";
                    sendHtmlResponse(exchange, 200, html);
                    return;
                } else {
                    String html = "<!doctype html>\n" +
                            "<html><head><meta charset=\"utf-8\">" +
                            "<meta http-equiv=\"refresh\" content=\"20;url=" + escapeHtml(redirectUrl) + "\">" +
                            "<title>アカウント有効化失敗</title>" +
                            "<style>" +
                            "body{font-family:Segoe UI, Roboto, Arial, Helvetica, sans-serif;background:#f4f6fb;color:#222;margin:0;padding:0;}" +
                            ".container{max-width:760px;margin:80px auto;padding:24px;background:#ffffff;border-radius:8px;box-shadow:0 6px 18px rgba(20,30,50,0.06);}" +
                            "h1{font-size:20px;margin:0 0 8px;color:#d6333f;}" +
                            "p{color:#444;margin:8px 0 0;}" +
                            "ul{margin-top:12px;padding-left:20px;color:#333;}" +
                            "a{color:#0b5ed7;text-decoration:none;} a:hover{text-decoration:underline;}" +
                            "</style></head><body>" +
                            "<div class=\"container\">" +
                            "<h1>アクティベーションに失敗しました。管理者に連絡してください</h1>" +
                            "<p>20秒後にログインページへ移動します。すぐに移動する場合はリンクをクリックしてください。</p>" +
                            "<ul>" +
                            "<li>ログインページ: <a href=\"" + escapeHtml(loginUrl) + "\">" + escapeHtml(loginUrl) + "</a></li>" +
                            "</ul>" +
                            "</div></body></html>";
                    sendHtmlResponse(exchange, 200, html);
                    return;
                }
            }

            sendJsonResponse(exchange, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            AppLogger.error("ActivationController error: " + e.getMessage());
            throw e;
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

    private void sendHtmlResponse(HttpExchange exchange, int statusCode, String html) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String escapeHtml(String str) {
        if (str == null) return null;
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }
}
