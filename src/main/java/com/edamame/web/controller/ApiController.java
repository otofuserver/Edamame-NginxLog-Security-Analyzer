package com.edamame.web.controller;

import com.edamame.web.service.DataService;
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
     * APIリクエストを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    public void handleApi(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            // CORS ヘッダーを設定
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

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
                default -> sendJsonError(exchange, 404, "API endpoint not found: " + endpoint);
            }

        } catch (Exception e) {
            logFunction.accept("API処理エラー: " + e.getMessage(), "ERROR");
            sendJsonError(exchange, 500, "Internal Server Error");
        }
    }

    /**
     * パスからエンドポイント名を抽出
     * @param path リクエストパス
     * @return エンドポイント名
     */
    private String extractEndpoint(String path) {
        // /api/stats -> stats
        String[] segments = path.split("/");
        if (segments.length >= 3) {
            return segments[2]; // /api/XXX の XXX 部分
        }
        return "";
    }

    /**
     * 統計情報APIを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleStatsApi(HttpExchange exchange) throws IOException {
        Map<String, Object> stats = dataService.getApiStats();
        sendJsonResponse(exchange, 200, stats);
        logFunction.accept("統計情報API呼び出し完了", "DEBUG");
    }

    /**
     * アラート情報APIを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAlertsApi(HttpExchange exchange) throws IOException {
        // クエリパラメータからlimitを取得（デフォルト20件）
        String query = exchange.getRequestURI().getQuery();
        int limit = parseIntParameter(query, "limit", 20);

        var alerts = dataService.getRecentAlerts(limit);
        Map<String, Object> response = Map.of(
            "alerts", alerts,
            "total", alerts.size(),
            "limit", limit
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("アラート情報API呼び出し完了 (件数: " + alerts.size() + ")", "DEBUG");
    }

    /**
     * サーバー情報APIを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleServersApi(HttpExchange exchange) throws IOException {
        var servers = dataService.getServerList();
        Map<String, Object> response = Map.of(
            "servers", servers,
            "total", servers.size()
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("サーバー情報API呼び出し完了 (サーバー数: " + servers.size() + ")", "DEBUG");
    }

    /**
     * 攻撃タイプ統計APIを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleAttackTypesApi(HttpExchange exchange) throws IOException {
        var attackTypes = dataService.getAttackTypeStats();
        Map<String, Object> response = Map.of(
            "attackTypes", attackTypes,
            "total", attackTypes.size()
        );

        sendJsonResponse(exchange, 200, response);
        logFunction.accept("攻撃タイプ統計API呼び出し完了", "DEBUG");
    }

    /**
     * ヘルスチェックAPIを処理
     * @param exchange HTTPエクスチェンジ
     * @throws IOException I/O例外
     */
    private void handleHealthApi(HttpExchange exchange) throws IOException {
        boolean dbConnected = dataService.isConnectionValid();
        Map<String, Object> health = Map.of(
            "status", dbConnected ? "healthy" : "unhealthy",
            "database", dbConnected ? "connected" : "disconnected",
            "timestamp", java.time.LocalDateTime.now().toString(),
            "version", "v1.0.0"
        );

        int statusCode = dbConnected ? 200 : 503;
        sendJsonResponse(exchange, statusCode, health);
        logFunction.accept("ヘルスチェックAPI呼び出し完了 (ステータス: " + (dbConnected ? "正常" : "異常") + ")", "DEBUG");
    }

    /**
     * クエリパラメータから整数値を解析
     * @param query クエリ文字列
     * @param paramName パラメータ名
     * @param defaultValue デフォルト値
     * @return 解析された整数値
     */
    private int parseIntParameter(String query, String paramName, int defaultValue) {
        if (query == null || query.isEmpty()) {
            return defaultValue;
        }

        for (String param : query.split("&")) {
            String[] keyValue = param.split("=");
            if (keyValue.length == 2 && paramName.equals(keyValue[0])) {
                try {
                    return Integer.parseInt(keyValue[1]);
                } catch (NumberFormatException e) {
                    logFunction.accept("パラメータ解析エラー: " + paramName + "=" + keyValue[1], "WARN");
                }
            }
        }

        return defaultValue;
    }

    /**
     * JSON レスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param data レスポンスデータ
     * @throws IOException I/O例外
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, Object data) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(data);

            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(statusCode, json.getBytes(StandardCharsets.UTF_8).length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            logFunction.accept("JSON レスポンス送信エラー: " + e.getMessage(), "ERROR");
            sendJsonError(exchange, 500, "JSON serialization error");
        }
    }

    /**
     * JSON エラーレスポンスを送信
     * @param exchange HTTPエクスチェンジ
     * @param statusCode ステータスコード
     * @param message エラーメッセージ
     * @throws IOException I/O例外
     */
    private void sendJsonError(HttpExchange exchange, int statusCode, String message) throws IOException {
        Map<String, Object> error = Map.of(
            "error", true,
            "status", statusCode,
            "message", message,
            "timestamp", java.time.LocalDateTime.now().toString()
        );

        String json = "{\"error\":true,\"status\":" + statusCode + ",\"message\":\"" + message + "\",\"timestamp\":\"" +
                     java.time.LocalDateTime.now().toString() + "\"}";

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, json.getBytes(StandardCharsets.UTF_8).length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        logFunction.accept("JSONエラーレスポンス送信: " + statusCode + " - " + message, "WARN");
    }
}
