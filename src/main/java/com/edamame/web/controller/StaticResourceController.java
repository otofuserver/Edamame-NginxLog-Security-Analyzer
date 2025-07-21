package com.edamame.web.controller;

import com.edamame.web.config.WebConfig;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * 静的リソースコントローラークラス
 * CSS、JavaScript、画像などの静的ファイル配信を担当
 */
public class StaticResourceController {

    private final WebConfig webConfig;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param webConfig Web設定
     * @param logFunction ログ出力関数
     */
    public StaticResourceController(WebConfig webConfig, BiConsumer<String, String> logFunction) {
        this.webConfig = webConfig;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
    }

    /**
     * 静的リソースリクエストを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleStaticResource(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String resourceName = extractResourceName(path);

            if (resourceName == null || resourceName.isEmpty()) {
                sendErrorResponse(exchange, 404, "Resource not found");
                return;
            }

            // リソースタイプを判定してコンテンツを取得
            String content = getResourceContent(resourceName);
            String contentType = getContentType(resourceName);

            if (content == null) {
                sendErrorResponse(exchange, 404, "Resource not found: " + resourceName);
                return;
            }

            // レスポンス送信
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600"); // 1時間キャッシュ
            exchange.sendResponseHeaders(200, content.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }

            logFunction.accept("静的リソース配信: " + resourceName, "DEBUG");

        } catch (Exception e) {
            logFunction.accept("静的リソース処理エラー: " + e.getMessage(), "ERROR");
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * faviconリクエストを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleFavicon(HttpExchange exchange) throws IOException {
        try {
            // 簡単なSVGアイコンを返す
            String favicon = generateFaviconSvg();

            exchange.getResponseHeaders().set("Content-Type", "image/svg+xml");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400"); // 24時間キャッシュ
            exchange.sendResponseHeaders(200, favicon.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(favicon.getBytes(StandardCharsets.UTF_8));
            }

            logFunction.accept("favicon配信完了", "DEBUG");

        } catch (Exception e) {
            logFunction.accept("favicon処理エラー: " + e.getMessage(), "ERROR");
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * パスからリソース名を抽出
     * @param path リクエストパス
     * @return リソース名
     */
    private String extractResourceName(String path) {
        // /css/dashboard.css -> dashboard.css
        // /js/dashboard.js -> dashboard.js
        // /static/xxx.xxx -> xxx.xxx
        String[] segments = path.split("/");
        if (segments.length >= 2) {
            return segments[segments.length - 1]; // 最後のセグメント
        }
        return null;
    }

    /**
     * リソースコンテンツを取得
     * @param resourceName リソース名
     * @return コンテンツ文字列
     */
    private String getResourceContent(String resourceName) {
        // Web設定から静的リソースを取得
        String content = webConfig.getStaticResource(resourceName);

        if (content != null && !content.isEmpty()) {
            return content;
        }

        // 標準的なリソースの場合はデフォルトを返す
        return switch (resourceName) {
            case "dashboard.css" -> webConfig.getStaticResource("dashboard.css");
            case "dashboard.js" -> webConfig.getStaticResource("dashboard.js");
            default -> null;
        };
    }

    /**
     * ファイル拡張子からコンテンツタイプを判定
     * @param resourceName リソース名
     * @return MIMEタイプ
     */
    private String getContentType(String resourceName) {
        if (resourceName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (resourceName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        } else if (resourceName.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        } else if (resourceName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (resourceName.endsWith(".png")) {
            return "image/png";
        } else if (resourceName.endsWith(".jpg") || resourceName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (resourceName.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (resourceName.endsWith(".ico")) {
            return "image/x-icon";
        } else {
            return "text/plain; charset=UTF-8";
        }
    }

    /**
     * シンプルなSVG faviconを生成
     * @return SVGコンテンツ
     */
    private String generateFaviconSvg() {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
                <rect width="32" height="32" fill="#3498db"/>
                <circle cx="16" cy="16" r="12" fill="#fff"/>
                <text x="16" y="20" text-anchor="middle" fill="#3498db" font-family="Arial" font-size="14" font-weight="bold">🛡️</text>
            </svg>
            """;
    }

    /**
     * エラーレスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        String errorHtml = String.format("""
            <!DOCTYPE html>
            <html>
            <head><title>%d - %s</title></head>
            <body>
                <h1>%d - %s</h1>
                <p>%s</p>
            </body>
            </html>
            """, statusCode, message, statusCode, message, message);

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, errorHtml.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(errorHtml.getBytes(StandardCharsets.UTF_8));
        }

        logFunction.accept(String.format("静的リソースエラーレスポンス: %d - %s", statusCode, message), "WARN");
    }
}
