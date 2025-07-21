package com.edamame.web;

import com.edamame.web.controller.DashboardController;
import com.edamame.web.controller.ApiController;
import com.edamame.web.controller.StaticResourceController;
import com.edamame.web.service.DataService;
import com.edamame.web.config.WebConfig;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BiConsumer;

/**
 * Webフロントエンドアプリケーションメインクラス
 * バックエンドとは独立したWebサーバーを提供し、ダッシュボード機能を担当
 */
public class WebApplication {

    private static final String WEB_APP_NAME = "Edamame Web Dashboard";
    private static final String WEB_APP_VERSION = "v1.0.0";
    private static final int DEFAULT_PORT = Integer.parseInt(getEnvOrDefault("WEB_PORT", "8080"));
    private static final String BIND_ADDRESS = getEnvOrDefault("WEB_BIND_ADDRESS", "0.0.0.0");

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private final BiConsumer<String, String> logFunction;
    private final Connection dbConnection;
    private DataService dataService;
    private WebConfig webConfig;

    /**
     * コンストラクタ
     * @param dbConnection データベース接続（バックエンドから共有）
     * @param logFunction ログ出力関数
     */
    public WebApplication(Connection dbConnection, BiConsumer<String, String> logFunction) {
        this.dbConnection = dbConnection;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
    }

    /**
     * 環境変数を取得し、存在しない場合はデフォルト値を返す
     * @param envVar 環境変数名
     * @param defaultValue デフォルト値
     * @return 環境変数の値またはデフォルト値
     */
    private static String getEnvOrDefault(String envVar, String defaultValue) {
        String value = System.getenv(envVar);
        return (value != null && !value.trim().isEmpty()) ? value.trim() : defaultValue;
    }

    /**
     * ログ出力（タイムスタンプ付き）
     * @param msg メッセージ
     * @param level ログレベル
     */
    private void log(String msg, String level) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        logFunction.accept(String.format("[WEB][%s] %s", level, msg), level);
    }

    /**
     * Webアプリケーションを初期化
     * @return 初期化成功可否
     */
    public boolean initialize() {
        log(String.format("%s %s 初期化中...", WEB_APP_NAME, WEB_APP_VERSION), "INFO");

        try {
            // Web設定を初期化
            webConfig = new WebConfig(logFunction);

            // データサービスを初期化
            dataService = new DataService(dbConnection, logFunction);

            // HTTPサーバーを作成
            server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, DEFAULT_PORT), 0);

            // 専用スレッドプールを作成（バックエンドとは独立）
            executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
            server.setExecutor(executor);

            // コントローラーを設定
            setupControllers();

            log("Webアプリケーション初期化完了", "INFO");
            return true;

        } catch (IOException e) {
            log("Webアプリケーション初期化エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * コントローラーをセットアップ
     */
    private void setupControllers() {
        // ダッシュボードコントローラー
        DashboardController dashboardController = new DashboardController(dataService, webConfig, logFunction);
        server.createContext("/", dashboardController::handleDashboard);
        server.createContext("/dashboard", dashboardController::handleDashboard);

        // APIコントローラー
        ApiController apiController = new ApiController(dataService, logFunction);
        server.createContext("/api/", apiController::handleApi);

        // 静的リソースコントローラー
        StaticResourceController staticController = new StaticResourceController(webConfig, logFunction);
        server.createContext("/static/", staticController::handleStaticResource);
        server.createContext("/css/", staticController::handleStaticResource);
        server.createContext("/js/", staticController::handleStaticResource);
        server.createContext("/favicon.ico", staticController::handleFavicon);

        log("コントローラー設定完了", "DEBUG");
    }

    /**
     * Webサーバーを開始
     * @return 開始成功可否
     */
    public boolean start() {
        try {
            server.start();
            log(String.format("Webサーバー開始: http://%s:%d", BIND_ADDRESS, DEFAULT_PORT), "INFO");
            log("ダッシュボードURL: http://" + BIND_ADDRESS + ":" + DEFAULT_PORT + "/dashboard", "INFO");
            return true;

        } catch (Exception e) {
            log("Webサーバー開始エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * Webサーバーを停止
     */
    public void stop() {
        log("Webサーバー停止中...", "INFO");

        if (server != null) {
            server.stop(5); // 5秒でグレースフル停止
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    log("Webスレッドプールを強制停止しました", "WARN");
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log("Webサーバー停止完了", "INFO");
    }

    /**
     * サーバーの稼働状況を取得
     * @return 状況文字列
     */
    public String getStatus() {
        if (server == null) {
            return "未初期化";
        }

        return String.format("稼働中 - %s:%d (アクティブスレッド: %d/%d)",
            BIND_ADDRESS, DEFAULT_PORT,
            executor != null ? executor.getActiveCount() : 0,
            executor != null ? executor.getPoolSize() : 0);
    }

    /**
     * データサービスを取得
     * @return データサービスインスタンス
     */
    public DataService getDataService() {
        return dataService;
    }

    /**
     * Web設定を取得
     * @return Web設定インスタンス
     */
    public WebConfig getWebConfig() {
        return webConfig;
    }
}
