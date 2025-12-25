package com.edamame.web;

import com.edamame.web.controller.*;
import com.edamame.web.security.AuthenticationFilter;
import com.edamame.web.security.AuthenticationService;
import com.edamame.web.service.DataService;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import com.edamame.security.tools.AppLogger;

/**
 * Webフロントエンドアプリケーションメインクラス
 * バックエンドとは独立したWebサーバーを提供し、ダッシュボード機能を担当
 */
public class WebApplication {

    private static final String WEB_APP_NAME = "Edamame Web Dashboard";
    private static final String WEB_APP_VERSION = "v1.1.0";
    private static final int DEFAULT_PORT = Integer.parseInt(getEnvOrDefault("WEB_PORT", "8080"));
    private static final String BIND_ADDRESS = getEnvOrDefault("WEB_BIND_ADDRESS", "0.0.0.0");

    private HttpServer server;
    private ThreadPoolExecutor executor;
    private DataService dataService;
    private AuthenticationService authService;

    /**
     * コンストラクタ
     */
    public WebApplication() {
    }

    /**
     * Webアプリケーションを初期化
     * @return 初期化成功時true
     */
    public boolean initialize() {
        try {
            initializeServices();
            AppLogger.info("Webアプリケーション初期化完了");
            return true;
        } catch (Exception e) {
            AppLogger.error("Webアプリケーション初期化エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * Webアプリケーションを開始
     * @param port リスニングポート
     * @return 開始成功時true
     */
    public boolean start(int port) {
        try {
            createHttpServer(port);
            setupRoutes();
            startServer();

            AppLogger.info(String.format("%s %s がポート %d で開始されました",
                WEB_APP_NAME, WEB_APP_VERSION, port));
            return true;

        } catch (Exception e) {
            AppLogger.error("Webアプリケーション開始エラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * デフォルトポートでWebアプリケーションを開始
     * @return 開始成功時true
     */
    public boolean start() {
        return start(DEFAULT_PORT);
    }

    /**
     * サービス層を初期化
     */
    private void initializeServices() {
        try {
            authService = new AuthenticationService();
            dataService = new DataService();
            AppLogger.info("サービス層初期化完了");
        } catch (Exception e) {
            AppLogger.error("サービス層初期化エラー: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * HTTPサーバーを作成
     * @param port リスニングポート
     * @throws IOException サーバー作成エラー
     */
    private void createHttpServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(BIND_ADDRESS, port), 0);

        // スレッドプールエグゼキューターを設定
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        server.setExecutor(executor);
    }

    /**
     * ルーティングを設定
     */
    private void setupRoutes() {
        // 認証が不要なエンドポイント
        server.createContext("/login", new LoginController(authService));
        server.createContext("/logout", new LogoutController(authService));

        // favicon.ico専用ルーティング（認証不要）
        server.createContext("/favicon.ico", exchange -> new StaticResourceController().handleFavicon(exchange));

        // 静的リソース（認証フィルターを通す）
        server.createContext("/static", new AuthenticationFilter(authService,
            new StaticResourceController()));

        // 認証が必要なエンドポイント
        server.createContext("/dashboard", new AuthenticationFilter(authService,
            new DashboardController(dataService)));

        // 新しいメインパス: /main を /dashboard と同じコントローラーで扱う
        server.createContext("/main", new AuthenticationFilter(authService,
            new DashboardController(dataService)));

        // /api は AJAX 呼び出しが多いため、フィルターでリダイレクトさせず ApiController 側で認証を扱う
        // 管理者専用のユーザー管理断片と検索APIは専用コントローラで処理
        server.createContext("/api/fragment/users", new UserManagementController(authService));
        server.createContext("/api/users", new UserManagementController(authService));

        server.createContext("/api", new ApiController(dataService, authService));

        // ルートパス（ダッシュボードにリダイレクト）
        server.createContext("/", new AuthenticationFilter(authService,
            new DashboardController(dataService)));
    }

    /**
     * サーバーを開始
     */
    private void startServer() {
        server.start();
    }

    /**
     * Webアプリケーションを停止
     */
    public void stop() {
        try {
            if (server != null) {
                server.stop(0);
                AppLogger.info("HTTPサーバーを停止しました");
            }
            if (executor != null) {
                executor.shutdown();
                AppLogger.info("スレッドプールを停止しました");
            }
            if (authService != null) {
                authService.shutdown();
                AppLogger.info("認証サービスを停止しました");
            }
        } catch (Exception e) {
            AppLogger.error("Webアプリケーション停止エラー: " + e.getMessage());
        }
    }

    /**
     * サーバーが起動中かどうかを判定
     * @return 起動中ならtrue
     */
    public boolean isRunning() {
        return server != null && executor != null && !executor.isShutdown();
    }

    /**
     * 環境変数またはデフォルト値を取得
     * @param envName 環境変数名
     * @param defaultValue デフォルト値
     * @return 環境変数値またはデフォルト値
     */
    private static String getEnvOrDefault(String envName, String defaultValue) {
        String value = System.getenv(envName);
        return value != null ? value : defaultValue;
    }
}
