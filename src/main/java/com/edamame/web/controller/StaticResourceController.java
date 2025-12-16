package com.edamame.web.controller;

import com.edamame.web.config.WebConfig;
import com.edamame.web.security.WebSecurityUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.edamame.security.tools.AppLogger;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 静的リソースコントローラークラス
 * CSS、JavaScript、画像などの静的ファイル配信を担当（XSS対策強化版）
 */
public class StaticResourceController implements HttpHandler {

    private final WebConfig webConfig;

    /**
     * コンストラクタ
     */
    public StaticResourceController() {
        this.webConfig = new WebConfig(); // WebConfigを内部で初期化

    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        handleStaticResource(exchange);
    }

    /**
     * 静的リソースリクエストを処理（XSS対策強化版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleStaticResource(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            // セキュリティヘッダーを設定
            applyStaticResourceSecurityHeaders(exchange);

            // リクエストのセキュリティ検証
            if (!isValidStaticResourceRequest(exchange)) {
                AppLogger.warn("不正な静的リソースリクエストを検知してブロックしました: " + exchange.getRequestURI());
                sendErrorResponse(exchange, 400, "Invalid Request");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String resourceName = extractSecureResourceName(path);

            if (resourceName == null || resourceName.isEmpty()) {
                sendErrorResponse(exchange, 404, "Resource not found");
                return;
            }

            // リソースタイプを判定してコンテンツを取得
            String content = getSecureResourceContent(resourceName);
            String contentType = getContentType(resourceName);

            if (content == null) {
                sendErrorResponse(exchange, 404, "Resource not found: " + WebSecurityUtils.sanitizeFilename(resourceName));
                return;
            }

            // レスポンス送信
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600"); // 1時間キャッシュ
            exchange.sendResponseHeaders(200, content.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }

            AppLogger.debug("静的リソース配信（セキュア版）: " + resourceName);

        } catch (Exception e) {
            AppLogger.error("静的リソース処理エラー: " + e.getMessage());
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * 静的リソース用セキュリティヘッダーを適用
     * @param exchange HTTPエクスチェンジ
     */
    private void applyStaticResourceSecurityHeaders(HttpExchange exchange) {
        // 基本セキュリティヘッダー
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

        // キャッシュ制御（静的リソースなのでキャッシュ許可）
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");

        // MIME sniffing防止
        exchange.getResponseHeaders().set("X-Download-Options", "noopen");
    }

    /**
     * 静的リソースリクエストのセキュリティ検証（肯定形メソッド名にリファクタリング）
     * @param exchange HTTPエクスチェンジ
     * @return 安全なリクエストの場合true
     */
    private boolean isValidStaticResourceRequest(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");

        // パスのチェック
        if ((WebSecurityUtils.detectXSS(path) || WebSecurityUtils.detectSQLInjection(path))) {
            AppLogger.warn("静的リソースで不正なパスを検知: " + path);
            return false;
        }

        // クエリパラメータのチェック（静的リソースでは基本的に不要だが念のため）
        if ((WebSecurityUtils.detectXSS(query) || WebSecurityUtils.detectSQLInjection(query))) {
            AppLogger.warn("静的リソースで不��なクエリを検知: " + query);
            return false;
        }

        // User-Agentの異常チェック
        if (WebSecurityUtils.detectXSS(userAgent)) {
            AppLogger.warn("静的リソースで不正なUser-Agentを検知: " + userAgent);
            return false;
        }

        // パストラバーサル攻撃のチェック
        if (path != null && (path.contains("../") || path.contains("..\\") || path.contains("%2e%2e"))) {
            AppLogger.warn("パストラバーサル攻撃を検知: " + path);
            return false;
        }

        return true;
    }

    /**
     * faviconリクエストを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleFavicon(HttpExchange exchange) throws IOException {
        try {
            // セキュリティ検証
            if (!isValidStaticResourceRequest(exchange)) {
                AppLogger.warn("不正なfaviconリクエストを検知してブロックしました");
                sendErrorResponse(exchange, 400, "Invalid Request");
                return;
            }

            // セキュリティヘッダーを設定
            applyStaticResourceSecurityHeaders(exchange);

            // favicon.icoのバイナリをresourcesから読み込む
            byte[] faviconBytes;
            try (var is = getClass().getClassLoader().getResourceAsStream("static/favicon.ico")) {
                if (is == null) {
                    AppLogger.warn("favicon.icoが見つかりません");
                    sendErrorResponse(exchange, 404, "favicon.ico not found");
                    return;
                }
                faviconBytes = is.readAllBytes();
            }

            exchange.getResponseHeaders().set("Content-Type", "image/x-icon");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400"); // 24時間キャッシュ
            exchange.sendResponseHeaders(200, faviconBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(faviconBytes);
            }

            AppLogger.debug("favicon.ico配信完了（バイナリ）");

        } catch (Exception e) {
            AppLogger.error("favicon処理エラー: " + e.getMessage());
            sendErrorResponse(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * パスからリソース名を抽出（セキュア版���
     * @param path リクエストパス
     * @return リソース名
     */
    private String extractSecureResourceName(String path) {
        if (path == null) {
            return null;
        }

        AppLogger.debug("リクエストパス: " + path);

        // パストラバーサル攻撃を防ぐ（サニタイズ前にチェック）
        if (path.contains("../") || path.contains("..\\") || path.contains("%2e%2e")) {
            AppLogger.warn("パストラバーサル攻撃の疑いがあるパス: " + path);
            return null;
        }

        // /css/dashboard.css -> dashboard.css
        // /js/dashboard.js -> dashboard.js
        // /static/xxx.xxx -> xxx.xxx
        String[] segments = path.split("/");
        if (segments.length >= 2) {
            String filename = segments[segments.length - 1]; // 最後のセグメント
            
            // ファイル名のみをサニタイズ（パス全体ではなく）
            String sanitizedFilename = filename.replaceAll("[^a-zA-Z0-9._-]", "");
            
            AppLogger.debug("抽出されたファイル名: " + filename + " -> サニタイズ後: " + sanitizedFilename);
            
            return sanitizedFilename.isEmpty() ? null : sanitizedFilename;
        }
        
        AppLogger.warn("ファイル名の抽出に失敗: " + path);
        return null;
    }

    /**
     * セキュアなリソースコンテンツを取得
     * @param resourceName リソース名
     * @return コンテンツ文字列
     */
    private String getSecureResourceContent(String resourceName) {
        // ファイル名を再度サニタイズ
        String sanitizedResourceName = WebSecurityUtils.sanitizeFilename(resourceName);

        // 許可されたファイル名のみを処理
        if (!isAllowedResource(sanitizedResourceName)) {
            AppLogger.warn("許可されていないリソースへのアクセス: " + resourceName);
            return null;
        }

        // Web設定から静的リソースを取得
        String content = webConfig.getStaticResource(sanitizedResourceName);

        if (content != null && !content.isEmpty()) {
            AppLogger.debug("静的リソース取得成功: " + sanitizedResourceName + " (長さ: " + content.length() + ")");
            return content;
        }

        // デフォルトコンテンツを生成
        AppLogger.warn("WebConfigからリソースが見つからないため、デフォルトを生成: " + sanitizedResourceName);
        return generateDefaultResource(sanitizedResourceName);
    }

    /**
     * デフォルトリソースを生成
     * @param resourceName リソース名
     * @return デフォルトコンテンツ
     */
    private String generateDefaultResource(String resourceName) {
        return switch (resourceName) {
            case "dashboard.css" -> """
                /* Edamame Security Dashboard - Default CSS */
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; background: #f5f5f5; }
                .container { max-width: 1200px; margin: 0 auto; padding: 20px; }
                .header { background: white; padding: 20px; border-radius: 8px; margin-bottom: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr)); gap: 20px; margin-bottom: 20px; }
                .stat-card { background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); text-align: center; }
                .stat-value { font-size: 2em; font-weight: bold; color: #2c3e50; }
                .alert-item { background: white; padding: 15px; border-radius: 8px; margin-bottom: 10px; border-left: 4px solid #3498db; }
                .server-item { background: white; padding: 15px; border-radius: 8px; margin-bottom: 10px; display: flex; justify-content: space-between; align-items: center; }
                """;
            case "dashboard.js" -> """
                // Edamame Security Dashboard - Default JavaScript
                console.log('Edamame Security Dashboard loaded');
                
                function startAutoRefresh(interval) {
                    setInterval(() => {
                        location.reload();
                    }, interval);
                }
                
                document.addEventListener('DOMContentLoaded', function() {
                    console.log('Dashboard initialized');
                });
                """;
            default -> null;
        };
    }

    /**
     * リソースが許可されているかチェック
     * @param resourceName リソース名
     * @return 許可されている場合true
     */
    private boolean isAllowedResource(String resourceName) {
        // 許可されたファイル拡張子
        String[] allowedExtensions = {".css", ".js", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico"};

        // 許可されたファイル名のパターン
        String[] allowedFiles = {"dashboard.css", "dashboard.js"};

        // ファイル名をチェック
        for (String allowedFile : allowedFiles) {
            if (resourceName.equals(allowedFile)) {
                return true;
            }
        }

        // 拡張子をチェック
        for (String ext : allowedExtensions) {
            if (resourceName.toLowerCase().endsWith(ext)) {
                // ファイル名の長さ制限
                return resourceName.length() <= 100;
            }
        }

        return false;
    }

    /**
     * ファイル拡張子からコンテンツタイプを判定（セキュア版）
     * @param resourceName リソース名
     * @return MIMEタイプ
     */
    private String getContentType(String resourceName) {
        if (resourceName == null) {
            return "text/plain; charset=UTF-8";
        }

        String lowerName = resourceName.toLowerCase();

        if (lowerName.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        } else if (lowerName.endsWith(".js")) {
            return "application/javascript; charset=UTF-8";
        } else if (lowerName.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        } else if (lowerName.endsWith(".json")) {
            return "application/json; charset=UTF-8";
        } else if (lowerName.endsWith(".png")) {
            return "image/png";
        } else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (lowerName.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (lowerName.endsWith(".ico")) {
            return "image/x-icon";
        } else {
            return "text/plain; charset=UTF-8";
        }
    }


    /**
     * エラーレスポンスを送信（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        // エラーメッセージをサニタイズ
        String sanitizedMessage = WebSecurityUtils.sanitizeInput(message);

        String errorHtml = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>%d - %s</title>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
            </head>
            <body>
                <h1>%d - %s</h1>
                <p>%s</p>
                <small>Security Enhanced Version</small>
            </body>
            </html>
            """, statusCode, sanitizedMessage, statusCode, sanitizedMessage, sanitizedMessage);

        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(statusCode, errorHtml.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(errorHtml.getBytes(StandardCharsets.UTF_8));
        }

        AppLogger.warn(String.format("静的リソースエラーレスポンス（セキュア版）: %d - %s", statusCode, sanitizedMessage));
    }
}
