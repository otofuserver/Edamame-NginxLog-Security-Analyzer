package com.edamame.web.controller;

import com.edamame.web.service.DataService;
import com.edamame.web.security.WebSecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * APIコントローラークラス
 * REST API エンドポイントの処理を担当
 */
public class ApiController {

    private final DataService dataService;
    private final BiConsumer<String, String> logFunction;
    private final ObjectMapper objectMapper;

    /**
     * コンストラクタ
     * @param dataService データサービス
     * @param logFunction ログ出力関数
     */
    public ApiController(DataService dataService, BiConsumer<String, String> logFunction) {
        this.dataService = dataService;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
        this.objectMapper = new ObjectMapper();
    }

    /**
     * APIリクエストを処理（XSS対策強化版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleApi(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // セ��ュリティヘッダーを設定（API版）
            applyApiSecurityHeaders(exchange);

            // リクエストのセキュリティ検証
            if (!validateApiRequest(exchange)) {
                logFunction.accept("不正なAPIリクエストを検知してブロックしました: " + path, "SECURITY");
                sendJsonError(exchange, 400, "Invalid Request - Security validation failed");
                return;
            }

            // OPTIONS リクエスト（プリフライト）への対応
            if ("OPTIONS".equals(method)) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().close();
                return;
            }

            // GETメソッドのみ許可
            if (!"GET".equals(method)) {
                sendJsonError(exchange, 405, "Method Not Allowed");
                return;
            }

            // パスに基づいてAPIエンドポイントを振り分け
            String endpoint = extractEndpoint(path);
            switch (endpoint) {
                case "stats" -> handleStatsApi(exchange);
                case "alerts" -> handleAlertsApi(exchange);
                case "servers" -> handleServersApi(exchange);
                case "attack-types" -> handleAttackTypesApi(exchange);
                case "health" -> handleHealthApi(exchange);
                default -> sendJsonError(exchange, 404, "API endpoint not found: " + WebSecurityUtils.sanitizeInput(endpoint));
            }

        } catch (Exception e) {
            logFunction.accept("API処理エラー: " + e.getMessage(), "ERROR");
            sendJsonError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * APIセキュリティヘッダーを適用
     * @param exchange HTTPエクスチェンジ
     */
    private void applyApiSecurityHeaders(HttpExchange exchange) {
        // API用セキュリティヘッダー
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");

        // CORS ヘッダー（制限的に設定）
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "3600");

        // キャッシュ制御
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.getResponseHeaders().set("Expires", "0");
    }

    /**
     * APIリクエストのセキュリティ検証
     * @param exchange HTTPエクスチェンジ
     * @return 安全なリクエストの場合true
     */
    private boolean validateApiRequest(HttpExchange exchange) {
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String referer = exchange.getRequestHeaders().getFirst("Referer");
        String query = exchange.getRequestURI().getQuery();
        String path = exchange.getRequestURI().getPath();

        // User-Agentのチェック
        if (userAgent != null && WebSecurityUtils.detectXSS(userAgent)) {
            logFunction.accept("APIで不正なUser-Agentを検知: " + userAgent, "SECURITY");
            return false;
        }

        // Refererのチェック
        if (referer != null && WebSecurityUtils.detectXSS(referer)) {
            logFunction.accept("APIで不正なRefererを検知: " + referer, "SECURITY");
            return false;
        }

        // パスのチェック
        if (path != null && (WebSecurityUtils.detectXSS(path) || WebSecurityUtils.detectSqlInjection(path))) {
            logFunction.accept("APIで不正なパスを検知: " + path, "SECURITY");
            return false;
        }

        // クエリパラメー���のチェック
        if (query != null && (WebSecurityUtils.detectXSS(query) || WebSecurityUtils.detectSqlInjection(query))) {
            logFunction.accept("APIで不正なクエリパラメータを検知: " + query, "SECURITY");
            return false;
        }

        return true;
    }

    /**
     * パスからエンドポイント名を抽出（セキュア版）
     * @param path リクエストパス
     * @return エンドポイント名
     */
    private String extractEndpoint(String path) {
        if (path == null) {
            return "";
        }

        // パスをサニタイズ
        String sanitizedPath = WebSecurityUtils.sanitizeInput(path);

        // /api/stats -> stats
        String[] segments = sanitizedPath.split("/");
        if (segments.length >= 3) {
            return segments[2]; // /api/XXX の XXX 部分
        }
        return "";
    }

    /**
     * 統計情報APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleStatsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = dataService.getApiStats();

        // データをサニタイズ
        Map<String, Object> sanitizedStats = sanitizeMapData(stats);

        sendJsonResponse(exchange, 200, sanitizedStats);
        logFunction.accept("統計情報API呼び出し完���（セキュア版）", "DEBUG");
    }

    /**
     * アラート情報APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAlertsApi(HttpExchange exchange) throws IOException {
        // クエリパラメータからlimitを取得（デフォルト20件）
        String query = exchange.getRequestURI().getQuery();
        int limit = parseSecureIntParameter(query, "limit", 20);

        var alerts = dataService.getRecentAlerts(limit);

        // アラートデータをサニタイズ
        var sanitizedAlerts = sanitizeListData(alerts);

        Map<String, Object> response = Map.of(
            "alerts", sanitizedAlerts,
            "total", sanitizedAlerts.size(),
            "limit", limit
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("アラート情報API呼び出し完了（セキュア版） (件数: " + sanitizedAlerts.size() + ")", "DEBUG");
    }

    /**
     * サーバー情報APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleServersApi(HttpExchange exchange) throws IOException {
        var servers = dataService.getServerList();

        // サーバーデータをサニタイズ
        var sanitizedServers = sanitizeListData(servers);

        Map<String, Object> response = Map.of(
            "servers", sanitizedServers,
            "total", sanitizedServers.size()
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("サーバー情報API呼び出し完了（セキュア版） (サーバー数: " + sanitizedServers.size() + ")", "DEBUG");
    }

    /**
     * 攻撃タイプ統計APIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAttackTypesApi(HttpExchange exchange) throws IOException {
        var attackTypes = dataService.getAttackTypeStats();

        // 攻撃タイプデータをサニタイズ
        var sanitizedAttackTypes = sanitizeListData(attackTypes);

        Map<String, Object> response = Map.of(
            "attackTypes", sanitizedAttackTypes,
            "total", sanitizedAttackTypes.size()
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("攻撃タイプ統計API呼び出し完了（セキュア版）", "DEBUG");
    }

    /**
     * ヘルスチェックAPIを処理（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleHealthApi(HttpExchange exchange) throws IOException {
        boolean dbConnected = dataService.isConnectionValid();
        Map<String, Object> health = Map.of(
            "status", dbConnected ? "healthy" : "unhealthy",
            "database", dbConnected ? "connected" : "disconnected",
            "timestamp", java.time.LocalDateTime.now().toString(),
            "version", "v1.0.0",
            "security", "enhanced" // セキュリティ強化版であることを明示
        );

        int statusCode = dbConnected ? 200 : 503;
        sendJsonResponse(exchange, statusCode, health);
        logFunction.accept("ヘルスチェックAPI呼び出し完了（セキュア版） (ステータス: " + (dbConnected ? "正常" : "異常") + ")", "DEBUG");
    }

    /**
     * Mapデータをサニタイズ
     * @param data 元データ
     * @return サニタイズ済みデータ
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMapData(Map<String, Object> data) {
        return data.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(
                entry -> WebSecurityUtils.sanitizeInput(entry.getKey()),
                entry -> {
                    Object value = entry.getValue();
                    if (value instanceof String str) {
                        return WebSecurityUtils.sanitizeInput(str);
                    } else if (value instanceof Map<?, ?> map) {
                        return sanitizeMapData((Map<String, Object>) map);
                    } else if (value instanceof java.util.List<?> list) {
                        return sanitizeListData((java.util.List<Map<String, Object>>) list);
                    }
                    return value;
                }
            ));
    }

    /**
     * Listデータをサニタイズ
     * @param data 元データ
     * @return サニタイズ済みデータ
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> sanitizeListData(java.util.List<Map<String, Object>> data) {
        return data.stream()
            .map(this::sanitizeMapData)
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * セキュアなクエリパラメータ解析
     * @param query クエリ文字列
     * @param paramName パラメータ名
     * @param defaultValue デフォルト値
     * @return 解析された整数値
     */
    private int parseSecureIntParameter(String query, String paramName, int defaultValue) {
        if (query == null || query.isEmpty()) {
            return defaultValue;
        }

        // クエリをサニタイズ
        String sanitizedQuery = WebSecurityUtils.sanitizeInput(query);

        for (String param : sanitizedQuery.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && paramName.equals(keyValue[0])) {
                try {
                    int value = Integer.parseInt(keyValue[1]);
                    // 値の範囲制限（DoS攻撃対策）
                    return Math.max(1, Math.min(value, 1000));
                } catch (NumberFormatException e) {
                    logFunction.accept("パラメータ解析エラー（セキュア版）: " + paramName + "=" + keyValue[1], "WARN");
                }
            }
        }

        return defaultValue;
    }

    /**
     * JSON レスポンスを送信（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param data レスポンスデータ
     * @throws IOException I/O例外
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(data);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, json.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            logFunction.accept("JSON レスポンス送信エラー（セキュア版）: " + e.getMessage(), "ERROR");
            sendJsonError(exchange, 500, "JSON serialization error");
        }
    }

    /**
     * JSON エラーレスポンスを送信（セキュア版）
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendJsonError(HttpExchange exchange, int statusCode, String message) throws IOException {
        // エラーメッセージもサニタイズ
        String sanitizedMessage = WebSecurityUtils.sanitizeInput(message);

        String json = "{\"error\":true,\"status\":" + statusCode + ",\"message\":\"" +
                     WebSecurityUtils.escapeJson(sanitizedMessage) + "\",\"timestamp\":\"" +
                     java.time.LocalDateTime.now().toString() + "\",\"security\":\"enhanced\"}";

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, json.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        logFunction.accept("JSONエラーレスポンス送信（セキュア版）: " + statusCode + " - " + sanitizedMessage, "WARN");
    }
}
