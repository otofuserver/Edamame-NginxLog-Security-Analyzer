package com.edamame.security;

import com.edamame.security.agent.AgentTcpServer;
import com.edamame.security.db.DbService;
import com.edamame.security.modsecurity.ModSecurityQueue;
import com.edamame.security.modsecurity.ModSecHandler;
import com.edamame.web.WebApplication;
import com.edamame.security.tools.AppLogger;
import org.json.JSONObject;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;

import java.nio.file.*;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NGINXログ監視・解析メインクラス
 * ログの監視とDB保存を担当
 * v1.1.0: DbServiceとDbSessionを導入してConnection手動管理を排除
 */
public class NginxLogToMysql {

    // アプリケーション定数
    private static final String APP_NAME = "Edamame NginxLog Security Analyzer";
    private static final String APP_VERSION = "v1.1.0";  // DbService導入により v1.1.0 に更新
    private static final String APP_AUTHOR = "Developed by Code Copilot";

    // パス設定（複数サーバー対応のためLOG_PATHは servers.conf から読み取り）
    private static final String SECURE_CONFIG_PATH = getEnvOrDefault("SECURE_CONFIG_PATH", "/run/secrets/db_config.enc");
    private static final String KEY_PATH = getEnvOrDefault("KEY_PATH", "/run/secrets/secret.key");
    private static final String ATTACK_PATTERNS_PATH = getEnvOrDefault("ATTACK_PATTERNS_PATH", "/app/config/attack_patterns.json");
    private static final String ATTACK_PATTERNS_YAML_PATH = getEnvOrDefault("ATTACK_PATTERNS_YAML_PATH", "/app/config/attack_patterns.yaml");
    private static final String ATTACK_PATTERNS_OVERRIDE_PATH = getEnvOrDefault("ATTACK_PATTERNS_OVERRIDE_PATH", "/app/config/attack_patterns_override.yaml");


    // Web設定
    private static final boolean ENABLE_WEB_FRONTEND = Boolean.parseBoolean(getEnvOrDefault("ENABLE_WEB_FRONTEND", "true"));
    private static final int WEB_PORT = Integer.parseInt(getEnvOrDefault("WEB_PORT", "8080"));

    // 設定値
    private static final int MAX_RETRIES = Integer.parseInt(getEnvOrDefault("MAX_RETRIES", "5"));
    private static final int RETRY_DELAY = Integer.parseInt(getEnvOrDefault("RETRY_DELAY", "3"));

    // v1.15.0で追加：エージェント連携モード用の設定
    private static final long ATTACK_PATTERN_UPDATE_INTERVAL = 3600 * 1000L; // 1時間

    // v1.15.0で追加：エージェント連携モード用の変数
    private static long lastAttackPatternUpdate = 0;

    // 定期メンテナンス用: 最終ログクリーンアップ実行時刻
    private static long lastLogCleanupTime = 0;
    private static final long LOG_CLEANUP_INTERVAL = 24 * 60 * 60 * 1000L; // 24時間

    // グローバル変数
    private static DbService dbService = null;  // Connection手動管理から DbService に変更
    private static boolean whitelistMode = false;
    private static String whitelistIp = "";
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);

    // ModSecurityアラートキュー（グローバル管理）
    private static ModSecurityQueue modSecurityQueue = null;
    private static ScheduledExecutorService modSecTaskExecutor = null;

    // アクション実行エンジン
    private static ActionEngine actionEngine = null;

    // 定時レポートマネージャー
    private static ScheduledReportManager reportManager = null;

    // Webフロントエンド
    private static WebApplication webApplication = null;
    private static ScheduledExecutorService webMonitorScheduler;

    // エージェントTCPサーバー
    private static AgentTcpServer agentTcpServer = null;


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
     * DBの接続情報を復号化して取得
     * @return DB接続情報のMap
     * @throws Exception 復号化または読み込み時のエラー
     */
    private static Map<String, String> loadDbConfig() throws Exception {
        // 秘密鍵を読み込み
        byte[] key = Files.readAllBytes(Paths.get(KEY_PATH));

        // 暗号化されたファイルを読み込み
        byte[] encryptedData = Files.readAllBytes(Paths.get(SECURE_CONFIG_PATH));

        // AES-GCMで復号化（新しいAPIを使用）
        @SuppressWarnings("deprecation")
        GCMBlockCipher cipher = new GCMBlockCipher(new AESEngine());

        // nonce（12バイト）を抽出
        byte[] nonce = new byte[12];
        System.arraycopy(encryptedData, 0, nonce, 0, 12);

        // 暗号化データを抽出
        byte[] cipherText = new byte[encryptedData.length - 12];
        System.arraycopy(encryptedData, 12, cipherText, 0, cipherText.length);

        // 復号化パラメータを設定
        AEADParameters params = new AEADParameters(new KeyParameter(key), 128, nonce);
        cipher.init(false, params);

        // 復号化実行
        byte[] decrypted = new byte[cipher.getOutputSize(cipherText.length)];
        int len = cipher.processBytes(cipherText, 0, cipherText.length, decrypted, 0);
        len += cipher.doFinal(decrypted, len);

        // JSON文字列として解析（org.json使用）
        String jsonStr = new String(decrypted, 0, len, StandardCharsets.UTF_8);
        JSONObject jsonObj = new JSONObject(jsonStr);
        
        Map<String, String> result = new HashMap<>();
        jsonObj.keys().forEachRemaining(jsonKey -> result.put(jsonKey, jsonObj.getString(jsonKey)));
        return result;
    }

    /**
     * DbServiceを初期化し、データベース接続を確立
     * @return DbService インスタンス（失敗時はnull）
     */
    private static DbService initializeDbService() {
        AppLogger.log("データベースサービス初期化中...", "INFO");

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                Map<String, String> config = loadDbConfig();

                String url = String.format("jdbc:mysql://%s/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo&characterEncoding=UTF-8&useUnicode=true",
                    config.get("host"), config.get("database"));

                Properties props = new Properties();
                props.setProperty("user", config.get("user"));
                props.setProperty("password", config.get("password"));
                props.setProperty("useUnicode", "true");
                props.setProperty("characterEncoding", "UTF-8");
                props.setProperty("autoReconnect", "true");
                props.setProperty("useSSL", "false");
                props.setProperty("allowPublicKeyRetrieval", "true");

                DbService service = new DbService(url, props);

                if (attempt > 1) {
                    AppLogger.log(String.format("DbService接続が復旧しました（リトライ #%d）", attempt), "RECOVERED");
                } else {
                    AppLogger.log("DbServiceが正常に初期化されました", "INFO");
                }

                return service;

            } catch (Exception e) {
                AppLogger.log(String.format("DbService初期化試行 #%d 失敗: %s", attempt, e.getMessage()), "ERROR");

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        AppLogger.log("リトライ待機中に割り込まれました", "WARN");
                        break;
                    }
                }
            }
        }

        AppLogger.log("すべてのDbService初期化試行が失敗しました", "CRITICAL");
        return null;
    }

    /**
     * アプリケーションの初期化処理
     * @return 初期化成功可否
     */
    private static boolean initializeApplication() {
        AppLogger.log(String.format("%s %s 起動中...", APP_NAME, APP_VERSION), "INFO");
        AppLogger.log(APP_AUTHOR, "INFO");

        // DbService初期化
        dbService = initializeDbService();
        if (dbService == null) {
            AppLogger.log("データベースサービスの初期化に失敗しました。アプリケーションを終了します。", "CRITICAL");
            return false;
        }

        // ModSecurityキューとタスクの初期化
        if (!initializeModSecurityQueue()) {
            AppLogger.log("ModSecurityキューの初期化に失敗しました", "ERROR");
            return false;
        }

        // DBスキーマの自動同期・移行を実行（DbService使用）
        try {
            dbService.syncAllTablesSchema();
            AppLogger.log("DBスキーマの自動同期・移行が完了しました", "INFO");
        } catch (Exception e) {
            AppLogger.log("DBスキーマ自動同期・移行でエラー: " + e.getMessage(), "CRITICAL");
            return false;
        }

        // 起動時の前回セッションクリーンアップ（エージェント一括inactive化）
        try {
            dbService.deactivateAllAgents();
            AppLogger.log("起動時クリーンアップ: 前回セッションの全エージェントをinactive状態に変更しました", "INFO");
        } catch (Exception e) {
            AppLogger.log("起動時エージェント一括inactive化でエラー: " + e.getMessage(), "WARN");
            // エラーでも処理続行（致命的ではない）
        }

        // 初期データ投入（DbService使用）
        try {
            dbService.initializeDefaultData(APP_VERSION);
        } catch (Exception e) {
            AppLogger.log("初期データ投入に失敗しました: " + e.getMessage(), "ERROR");
            return false;
        }

        // ホワイトリスト設定を読み込み（DbService使用）
        loadWhitelistSettings();

        // 攻撃パターンファイルの自動更新を起動時に試行
        try {
            AttackPattern.updateIfNeeded(ATTACK_PATTERNS_PATH);
            AppLogger.log("攻撃パターンファイルの更新チェック完了 (バージョン: " + AttackPattern.getVersion(ATTACK_PATTERNS_PATH) + ")", "INFO");
        } catch (Exception e) {
            AppLogger.log("攻撃パターンファイルの更新チェックでエラー: " + e.getMessage(), "WARN");
        }

        // 攻撃パターンファイルの確認
        if (!AttackPattern.isAttackPatternsFileAvailable(ATTACK_PATTERNS_PATH)) {
            AppLogger.log("攻撃パターンファイルが見つかりません: " + ATTACK_PATTERNS_PATH, "WARN");
        } else {
            AppLogger.log("攻撃パターンファイル確認完了 (バージョン: " +
                AttackPattern.getVersion(ATTACK_PATTERNS_PATH) + ", パターン数: " +
                AttackPattern.getPatternCountYaml(ATTACK_PATTERNS_PATH) + ")", "INFO");
            // オーバーライドファイルの存在判定
            if (AttackPattern.isAttackPatternsOverrideAvailable(ATTACK_PATTERNS_OVERRIDE_PATH)) {
                AppLogger.log("攻撃パターンオーバーライドファイルが有効です: " + ATTACK_PATTERNS_OVERRIDE_PATH, "INFO");
            } else {
                AppLogger.log("攻撃パターンオーバーライドファイルは未検出です: " + ATTACK_PATTERNS_OVERRIDE_PATH, "DEBUG");
            }
        }

        // ActionEngineの初期化（DbService使用）
        try {
            actionEngine = new ActionEngine(dbService);
            AppLogger.log("ActionEngine初期化完了", "INFO");
        } catch (Exception e) {
            AppLogger.log("ActionEngine初期化でエラー: " + e.getMessage(), "ERROR");
            return false;
        }

        // エージェントTCPサーバーの初期化（ModSecurityキューを渡す）
        try {
            agentTcpServer = new AgentTcpServer(dbService, modSecurityQueue);
            agentTcpServer.start();
            AppLogger.log("エージェントTCPサーバー起動完了 (ポート: 2591)", "INFO");
        } catch (Exception e) {
            AppLogger.log("エージェントTCPサーバーの起動に失敗しました: " + e.getMessage(), "ERROR");
            // エージェントサーバーの失敗は致命的ではないため、処理を続行
        }

        // 定時レポートマネージャーの初期化
        reportManager = new ScheduledReportManager(actionEngine);
        reportManager.startScheduledReports();
        AppLogger.log("ScheduledReportManager初期化・開始完了", "INFO");

        // Webフロントエンドの初期化（オプション）
        if (ENABLE_WEB_FRONTEND) {
            if (!initializeWebFrontend()) {
                AppLogger.log("Webフロントエンドの初期化に失敗しました。バックエンドのみで続行します。", "WARN");
            }
        } else {
            AppLogger.log("Webフロントエンドは無効に設定されています", "INFO");
        }

        // 起動時にログ自動削除バッチも実行（DbService使用）
        dbService.runLogCleanupBatch();
        AppLogger.log("起動時にログ自動削除バッチを実行しました", "INFO");

        AppLogger.log("初期化処理が完了しました", "INFO");
        return true;
    }

    /**
     * Webフロントエンドを初期化
     * @return 初期化成功可否
     */
    private static boolean initializeWebFrontend() {
        try {
            AppLogger.log("Webフロントエンド初期化中...", "INFO");

            // WebApplicationインスタンスを作成（DbService使用）
            webApplication = new WebApplication(dbService);

            // 初期化
            if (!webApplication.initialize()) {
                AppLogger.log("WebApplication初期化に失敗しました", "ERROR");
                return false;
            }

            // ScheduledExecutorServiceでWebサーバーの監視を定期実行
            webMonitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "WebFrontendMonitor");
                t.setDaemon(true);
                return t;
            });
            webMonitorScheduler.scheduleAtFixedRate(() -> {
                if (!isRunning.get()) return;
                // Webサーバーが既に起動済みかチェックし、未起動時のみstart()を呼ぶ
                try {
                    if (!webApplication.isRunning()) {
                        webApplication.start();
                    }
                } catch (Exception e) {
                    AppLogger.log("Webフロントエンド監視スレッドでエラー: " + e.getMessage(), "ERROR");
                }
            }, 0, 5, TimeUnit.SECONDS);

            AppLogger.log("Webフロントエンドスレッド開始完了", "INFO");
            AppLogger.log("ダッシュボードURL: http://localhost:" + WEB_PORT + "/dashboard", "INFO");

            return true;

        } catch (Exception e) {
            AppLogger.log("Webフロントエンド初期化エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * ホワイトリスト設定をDBから読み込み（DbService使用）
     */
    private static void loadWhitelistSettings() {
        try {
            Map<String, Object> settings = dbService.selectWhitelistSettings();
            if (settings != null) {
                whitelistMode = (Boolean) settings.get("whitelist_mode");
                whitelistIp = (String) settings.get("whitelist_ip");
                AppLogger.log("ホワイトリスト設定読み込み完了 (モード: " + whitelistMode + ", IP: " + whitelistIp + ")", "INFO");
            }
        } catch (SQLException e) {
            AppLogger.log("ホワイトリスト設定の読み込みでエラー: " + e.getMessage(), "WARN");
        }
    }


    /**
     * アプリケーションのクリーンアップ処理
     */
    private static void cleanup() {
        AppLogger.log("アプリケーションをシャットダウン中...", "INFO");

        // ModSecurityタスクを停止
        if (modSecTaskExecutor != null && !modSecTaskExecutor.isShutdown()) {
            modSecTaskExecutor.shutdown();
            try {
                if (!modSecTaskExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    modSecTaskExecutor.shutdownNow();
                }
                AppLogger.log("ModSecurityタスク停止完了", "INFO");
            } catch (InterruptedException e) {
                modSecTaskExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Webフロントエンドを停止
        if (webApplication != null) {
            try {
                webApplication.stop();
                AppLogger.log("Webフロントエンド停止完了", "INFO");
            } catch (Exception e) {
                AppLogger.log("Webフロントエンド停止中にエラー: " + e.getMessage(), "WARN");
            }
        }

        // DbServiceのクローズ（SQLExceptionは発生しない）
        if (dbService != null) {
            dbService.close();
            AppLogger.log("データベース接続を閉じました", "INFO");
        }

        AppLogger.log("クリーンアップが完了しました", "INFO");
    }


    /**
     * メインメソッド
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {

        try {
            AppLogger.log("====================================", "INFO");
            AppLogger.log(APP_NAME + " " + APP_VERSION + " を開始します", "INFO");
            AppLogger.log(APP_AUTHOR, "INFO");
            AppLogger.log("====================================", "INFO");
            AppLogger.log("エージェント連携モード（v1.15.0）", "INFO");

            // 初期化処理
            if (!initializeApplication()) {
                AppLogger.log("初期化に失敗しました。アプリケーションを終了します", "CRITICAL");
                System.exit(1);
            }

            AppLogger.log("エージェント連携システムが起動しました", "INFO");
            AppLogger.log("エージェントからのログ受信を待機中...", "INFO");

            // メインループ（エージェント連携モード）
            while (isRunning.get()) {
                try {
                    // 定期的なメンテナンス処理
                    performMaintenanceTasks();

                    // 5秒間隔で待機
                    Thread.sleep(5000);

                } catch (InterruptedException e) {
                    AppLogger.log("メインスレッドが中断されました", "INFO");
                    break;
                } catch (Exception e) {
                    AppLogger.log("メインループでエラーが発生しました: " + e.getMessage(), "ERROR");
                    if (e instanceof SQLException) {
                        // DB接続エラーの場合は再接続を試行
                        dbService = null;
                        Thread.sleep(5000);
                    }
                }
            }

        } catch (Exception e) {
            AppLogger.log("予期��ないエラーが発生しました: " + e.getMessage(), "CRITICAL");
            AppLogger.log("エラー詳細: " + e.getClass().getSimpleName() + " - " + e.toString(), "ERROR");
        } finally {
            cleanup();
        }
    }

    /**
     * 定期メンテナンス処理（v1.1.0版：DbService使用）
     */
    private static void performMaintenanceTasks() {
        try {
            // DbService接続状態チェック
            if (dbService == null || !dbService.isConnected()) {
                AppLogger.log("DbServiceが利用できません。再初期化を試行します。", "WARN");
                dbService = initializeDbService();
                if (dbService == null) {
                    AppLogger.log("DbServiceの初期化に失敗しました", "ERROR");
                    return;
                }
            }

            // 24時間ごとにログ自動削除バッチを実行（DbService使用）
            if (System.currentTimeMillis() - lastLogCleanupTime > LOG_CLEANUP_INTERVAL) {
                dbService.runLogCleanupBatch();
                lastLogCleanupTime = System.currentTimeMillis();
                AppLogger.log("定期メンテナンス: ログ自動削除バッチを実行しました", "INFO");
            }

            // 攻撃パターン更新（YAML版）
            if (System.currentTimeMillis() - lastAttackPatternUpdate > ATTACK_PATTERN_UPDATE_INTERVAL) {
                try {
                    AttackPattern.updateIfNeeded(ATTACK_PATTERNS_YAML_PATH);
                    lastAttackPatternUpdate = System.currentTimeMillis();
                } catch (Exception e) {
                    AppLogger.log("攻撃パターン更新中にエラー: " + e.getMessage(), "WARN");
                }
            }


        } catch (Exception e) {
            AppLogger.log("メンテナンス処理でエラー: " + e.getMessage(), "WARN");
        }
    }

    /**
     * ModSecurityキューとタスクを初期化
     * @return 初期化成功可否
     */
    private static boolean initializeModSecurityQueue() {
        try {
            AppLogger.log("ModSecurityキュー初期化中...", "INFO");

            // ModSecurityキューを作成
            modSecurityQueue = new ModSecurityQueue();

            // ModSecurityタスク実行用のExecutorServiceを初期化
            modSecTaskExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ModSecTaskExecutor");
                t.setDaemon(true);
                return t;
            });

            // クリーンアップタスクを開始
            modSecurityQueue.startCleanupTask(modSecTaskExecutor);

            // 定期的アラート照合タスクを開始
            ModSecHandler.startPeriodicAlertMatching(modSecTaskExecutor, modSecurityQueue, dbService);

            AppLogger.log("ModSecurityキューとタスク初期化完了", "INFO");
            return true;

        } catch (Exception e) {
            AppLogger.log("ModSecurityキュー初期化エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }
}
