package com.edamame.security;

import org.json.JSONObject;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.charset.StandardCharsets;

/**
 * NGINXログ監視・解析メインクラス
 * ログの監視とDB保存を担当
 */
public class NginxLogToMysql {

    private static final Logger logger = LoggerFactory.getLogger(NginxLogToMysql.class);

    // アプリケーション定数
    private static final String APP_NAME = "Edamame NginxLog Security Analyzer";
    private static final String APP_VERSION = "v1.0.33";
    private static final String APP_AUTHOR = "Developed by Code Copilot";

    // パス設定
    private static final String LOG_PATH = getEnvOrDefault("LOG_PATH", "/var/log/nginx/nginx.log");
    private static final String SECURE_CONFIG_PATH = getEnvOrDefault("SECURE_CONFIG_PATH", "/run/secrets/db_config.enc");
    private static final String KEY_PATH = getEnvOrDefault("KEY_PATH", "/run/secrets/secret.key");
    private static final String ATTACK_PATTERNS_PATH = getEnvOrDefault("ATTACK_PATTERNS_PATH", "/app/config/attack_patterns.json");
    private static final String SERVERS_CONFIG_PATH = getEnvOrDefault("SERVERS_CONFIG_PATH", "/app/config/servers.conf");

    // 設定値
    private static final int MAX_RETRIES = Integer.parseInt(getEnvOrDefault("MAX_RETRIES", "5"));
    private static final int RETRY_DELAY = Integer.parseInt(getEnvOrDefault("RETRY_DELAY", "3"));
    private static final int ATTACK_PATTERNS_CHECK_INTERVAL = Integer.parseInt(getEnvOrDefault("ATTACK_PATTERNS_CHECK_INTERVAL", "3600"));
    private static final int SERVERS_CONFIG_CHECK_INTERVAL = Integer.parseInt(getEnvOrDefault("SERVERS_CONFIG_CHECK_INTERVAL", "300"));

    // グローバル変数
    private static Connection dbSession = null;
    private static boolean whitelistMode = false;
    private static String whitelistIp = "";
    private static long lastAttackPatternsCheck = 0;
    private static long lastServersConfigCheck = 0;
    private static final AtomicBoolean isRunning = new AtomicBoolean(true);

    // ModSecurity状態管理用の変数を追加
    private static String pendingModSecLine = null;
    private static boolean hasPendingModSecAlert = false;

    // 複数サーバー監視用
    private static ServerConfig serverConfig = null;
    private static final Map<String, LogMonitor> logMonitors = new HashMap<>();

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
     * タイムスタンプ＋ログレベル付きで標準出力に出す共通関数
     * @param msg メッセージ
     * @param level ログレベル
     */
    private static void log(String msg, String level) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // DEBUGレベルのログは環境変数で制御可能
        if ("DEBUG".equals(level)) {
            String debugEnabled = getEnvOrDefault("NGINX_LOG_DEBUG", "false");
            if (!"true".equalsIgnoreCase(debugEnabled)) {
                return;
            }
        }

        System.out.printf("[%s][%s] %s%n", timestamp, level, msg);
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
        jsonObj.keys().forEachRemaining(jsonKey -> {
            result.put(jsonKey, jsonObj.getString(jsonKey));
        });
        return result;
    }

    /**
     * DBへ接続（失敗時は最大N回リトライ）
     * @return DB接続オブジェクト
     */
    private static Connection dbConnect() {
        if (dbSession != null) {
            try {
                if (!dbSession.isClosed()) {
                    return dbSession;
                }
            } catch (SQLException e) {
                log("DB接続チェック中にエラーが発生しました: " + e.getMessage(), "WARN");
            }
        }

        log("データベースへの接続を試行中...", "INFO");

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

                dbSession = DriverManager.getConnection(url, props);

                if (attempt > 1) {
                    log(String.format("DB接続が復旧しました（リトライ #%d）", attempt), "RECOVERED");
                } else {
                    log("データベースに正常に接続しました", "INFO");
                }

                return dbSession;

            } catch (Exception e) {
                log(String.format("DB接続試行 #%d 失敗: %s", attempt, e.getMessage()), "ERROR");

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log("リトライ待機中に割り込まれました", "WARN");
                        break;
                    }
                }
            }
        }

        log("すべてのDB接続試行が失敗しました", "CRITICAL");
        return null;
    }

    /**
     * アプリケーションの初期化処理
     * @return 初期化成功可否
     */
    private static boolean initializeApplication() {
        log(String.format("%s %s 起動中...", APP_NAME, APP_VERSION), "INFO");
        log(APP_AUTHOR, "INFO");

        // DB接続テスト
        Connection conn = dbConnect();
        if (conn == null) {
            log("データベース接続に失敗しました。アプリケーションを終了します。", "CRITICAL");
            return false;
        }

        // テーブル作成とスキーマ確認処理
        if (!DbSchema.createInitialTables(conn, APP_VERSION, NginxLogToMysql::log)) {
            log("データベーステーブルの作成に失敗しました。", "CRITICAL");
            return false;
        }

        if (!DbSchema.ensureAllRequiredColumns(conn, NginxLogToMysql::log)) {
            log("データベースカラムの確認に失敗しました。", "WARN");
        }

        // 複数サーバー対応スキーマ更新
        if (!DbSchema.addMultiServerSupport(conn, NginxLogToMysql::log)) {
            log("複数サーバー対応スキーマ更新に失敗しました。", "WARN");
        }

        // ホワイトリスト設定を読み込み
        loadWhitelistSettings(conn);

        // 攻撃パターンファイルの確認
        if (!AttackPattern.isAttackPatternsFileAvailable(ATTACK_PATTERNS_PATH)) {
            log("攻撃パターンファイルが見つかりません: " + ATTACK_PATTERNS_PATH, "WARN");
        } else {
            log("攻撃パターンファイル確認完了 (バージョン: " +
                AttackPattern.getVersion(ATTACK_PATTERNS_PATH) + ", パターン数: " +
                AttackPattern.getPatternCount(ATTACK_PATTERNS_PATH) + ")", "INFO");
        }

        // 複数サーバー設定の初期化
        serverConfig = ServerConfig.createDefault(NginxLogToMysql::log);
        if (!serverConfig.loadServers()) {
            log("サーバー設定の読み込みに失敗しました。", "CRITICAL");
            log("servers.confファイルを確認してください: " + SERVERS_CONFIG_PATH, "ERROR");
            return false;
        }

        // 複数サーバー監視の初期化
        if (!initializeMultiServerMonitoring()) {
            log("複数サーバー監視の初期化に失敗しました。", "CRITICAL");
            return false;
        }

        log("初期化処理が完了しました", "INFO");
        return true;
    }

    /**
     * 複数サーバー監視の初期化
     * @return 初期化成功可否
     */
    private static boolean initializeMultiServerMonitoring() {
        log("複数サーバー監視を初期化中...", "INFO");

        List<ServerConfig.ServerInfo> servers = serverConfig.getServers();
        if (servers.isEmpty()) {
            log("監視対象サーバーが設定されていません", "ERROR");
            return false;
        }

        // 設定概要を表示
        serverConfig.displayConfigSummary();

        // ログファイルの存在確認
        List<ServerConfig.ServerInfo> missingFiles = serverConfig.validateLogFiles();
        if (!missingFiles.isEmpty()) {
            log("一部のログファイルが見つかりません。該当サーバーの監視はスキップされます。", "WARN");
        }

        // 各サーバー用のLogMonitorを作成
        int successCount = 0;
        for (ServerConfig.ServerInfo server : servers) {
            if (server.logFileExists()) {
                LogMonitor monitor = new LogMonitor(server, NginxLogToMysql::log, NginxLogToMysql::processEnhancedLogLine);
                logMonitors.put(server.name(), monitor);
                successCount++;
                log("サーバー監視準備完了: " + server.name(), "DEBUG");
            } else {
                log("ログファイル不存在のためスキップ: " + server.name() + " → " + server.logPath(), "WARN");
            }
        }

        if (successCount == 0) {
            log("監視可能なサーバーがありません", "CRITICAL");
            return false;
        }

        log(String.format("複数サーバー監視初期化完了: %d/%d サーバーが監視可能",
            successCount, servers.size()), "INFO");
        return true;
    }

    /**
     * ホワイトリスト設定をDBから読み込み
     * @param conn データベース接続
     */
    private static void loadWhitelistSettings(Connection conn) {
        try (PreparedStatement pstmt = conn.prepareStatement(
            "SELECT whitelist_mode, whitelist_ip FROM settings WHERE id = 1")) {
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                whitelistMode = rs.getBoolean("whitelist_mode");
                whitelistIp = rs.getString("whitelist_ip");
                log("ホワイトリスト設定読み込み完了 (モード: " + whitelistMode + ", IP: " + whitelistIp + ")", "INFO");
            }
        } catch (SQLException e) {
            log("ホワイトリスト設定の読み込みでエラー: " + e.getMessage(), "WARN");
        }
    }

    /**
     * 攻撃パターンファイルの定期更新チェック（1時間ごと）
     */
    private static void checkAttackPatternsUpdate() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCheck = currentTime - lastAttackPatternsCheck;

        // デバッグ情報（初回起動時）
        if (lastAttackPatternsCheck == 0) {
            log("Attack patterns check initialized. Next check in " + ATTACK_PATTERNS_CHECK_INTERVAL + " seconds", "DEBUG");
            lastAttackPatternsCheck = currentTime;
            return;
        }

        // 1時間（ATTACK_PATTERNS_CHECK_INTERVAL秒）経過チェック
        if (timeSinceLastCheck > ATTACK_PATTERNS_CHECK_INTERVAL * 1000L) {
            log("Starting attack patterns update check...", "INFO");

            try {
                boolean updated = AttackPattern.updateIfNeeded(ATTACK_PATTERNS_PATH, NginxLogToMysql::log);
                if (updated) {
                    log("Attack patterns file updated (version: " +
                        AttackPattern.getVersion(ATTACK_PATTERNS_PATH) + ")", "INFO");
                } else {
                    log("Attack patterns file is up to date (version: " +
                        AttackPattern.getVersion(ATTACK_PATTERNS_PATH) + ")", "DEBUG");
                }
            } catch (Exception e) {
                log("Error during attack patterns update check: " + e.getMessage(), "WARN");
            }

            lastAttackPatternsCheck = currentTime;
            log("Attack patterns check completed. Next check scheduled in " + ATTACK_PATTERNS_CHECK_INTERVAL + " seconds", "DEBUG");
        }
    }

    /**
     * サーバー設定ファイルの定期更新チェック（5分ごと）
     */
    private static void checkServersConfigUpdate() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastCheck = currentTime - lastServersConfigCheck;

        // デバッグ情報（初回起動時）
        if (lastServersConfigCheck == 0) {
            log("Servers config check initialized. Next check in " + SERVERS_CONFIG_CHECK_INTERVAL + " seconds", "DEBUG");
            lastServersConfigCheck = currentTime;
            return;
        }

        // 5分（SERVERS_CONFIG_CHECK_INTERVAL秒）経過チェック
        if (timeSinceLastCheck > SERVERS_CONFIG_CHECK_INTERVAL * 1000L) {
            log("Starting servers config update check...", "INFO");

            try {
                boolean updated = ServerConfig.updateIfNeeded(SERVERS_CONFIG_PATH, NginxLogToMysql::log);
                if (updated) {
                    log("Servers config file updated", "INFO");
                } else {
                    log("Servers config file is up to date", "DEBUG");
                }
            } catch (Exception e) {
                log("Error during servers config update check: " + e.getMessage(), "WARN");
            }

            lastServersConfigCheck = currentTime;
            log("Servers config check completed. Next check scheduled in " + SERVERS_CONFIG_CHECK_INTERVAL + " seconds", "DEBUG");
        }
    }

    /**
     * ログ行を処理してデータベースに保存
     * @param line ログの1行
     */
    private static void processLogLine(String line) {
        try {
            // 1. ModSecurity詳細行かどうか判定
            if (ModSecHandler.detectModsecBlock(line)) {
                // ModSecurity: Access denied の行は一時保存し、次のリクエスト行で利用
                pendingModSecLine = line;
                hasPendingModSecAlert = true;
                log("ModSecurity行として処理済み: " + line.substring(0, Math.min(100, line.length())), "DEBUG");
                return; // ModSecurity行は保存せずに終了
            }

            // 2. ログ行をパース（HTTPリクエスト行のみ）
            Map<String, Object> logData = LogParser.parseLogLine(line, NginxLogToMysql::log);
            if (logData == null) {
                // パースできない行の場合、保留中のModSec情報をリセット
                if (hasPendingModSecAlert) {
                    hasPendingModSecAlert = false;
                    pendingModSecLine = null;
                }
                return; // パースできない行はスキップ
            }

            Connection conn = dbConnect();
            if (conn == null) {
                log("DB接続が利用できません。ログ行をスキップします。", "ERROR");
                return;
            }

            // 3. 直前にModSecurity: Access denied行があればblocked扱い
            boolean isModSecBlocked = hasPendingModSecAlert;
            logData.put("blocked_by_modsec", isModSecBlocked);

            // アクセスログをDBに保存
            long logId = saveAccessLog(conn, logData);
            if (logId > 0) {
                // URL登録処理
                registerUrl(conn, logData);

                // 5. ModSecurityアラートがあれば保存
                if (isModSecBlocked && pendingModSecLine != null) {
                    List<Map<String, String>> alerts = ModSecHandler.parseModsecAlert(pendingModSecLine, NginxLogToMysql::log);
                    if (ModSecHandler.saveModsecAlerts(conn, logId, alerts, NginxLogToMysql::log)) {
                        log("ModSecurityアラート保存完了 (アクセスログID: " + logId + ")", "DEBUG");
                    }
                }

                log("ログ処理完了: " + logData.get("method") + " " + logData.get("full_url") +
                    " (ID: " + logId + ", Blocked: " + isModSecBlocked + ")", "DEBUG");
            }

            // 処理完了後、保留中のModSec情報をクリア
            if (hasPendingModSecAlert) {
                hasPendingModSecAlert = false;
                pendingModSecLine = null;
            }

        } catch (Exception e) {
            log("ログ行処理中にエラー: " + e.getMessage(), "ERROR");
            // エラー時も保留中のModSec情報をクリア
            if (hasPendingModSecAlert) {
                hasPendingModSecAlert = false;
                pendingModSecLine = null;
            }
        }
    }

    /**
     * アクセスログをデータベースに保存
     * @param conn データベース接続
     * @param logData ログデータ
     * @return 保存されたレコードのID（失敗時は-1）
     */
    private static long saveAccessLog(Connection conn, Map<String, Object> logData) {
        String sql = "INSERT INTO access_log (method, full_url, status_code, ip_address, access_time, blocked_by_modsec, server_name) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, (String) logData.get("method"));
            pstmt.setString(2, (String) logData.get("full_url"));
            pstmt.setInt(3, (Integer) logData.get("status_code"));
            pstmt.setString(4, (String) logData.get("ip_address"));
            pstmt.setTimestamp(5, Timestamp.valueOf((LocalDateTime) logData.get("access_time")));
            pstmt.setBoolean(6, (Boolean) logData.get("blocked_by_modsec"));
            pstmt.setString(7, (String) logData.getOrDefault("server_name", "default"));

            int affectedRows = pstmt.executeUpdate();
            if (affectedRows > 0) {
                ResultSet generatedKeys = pstmt.getGeneratedKeys();
                if (generatedKeys.next()) {
                    return generatedKeys.getLong(1);
                }
            }
        } catch (SQLException e) {
            log("アクセスログ保存エラー: " + e.getMessage(), "ERROR");
        }

        return -1;
    }

    /**
     * URLを登録テーブルに追加（初回アクセス時のみ）
     * @param conn データベース接続
     * @param logData ログデータ
     */
    private static void registerUrl(Connection conn, Map<String, Object> logData) {
        String method = (String) logData.get("method");
        String fullUrl = (String) logData.get("full_url");
        String ipAddress = (String) logData.get("ip_address");
        String serverName = (String) logData.getOrDefault("server_name", "default");

        // 既存URLチェック（同じサーバーからの同じURL）
        String checkSql = "SELECT id FROM url_registry WHERE method = ? AND full_url = ? AND server_name = ?";
        try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            checkStmt.setString(1, method);
            checkStmt.setString(2, fullUrl);
            checkStmt.setString(3, serverName);
            ResultSet rs = checkStmt.executeQuery();

            if (rs.next()) {
                return; // 既に登録済み
            }
        } catch (SQLException e) {
            log("URL存在チェックエラー: " + e.getMessage(), "WARN");
            return;
        }

        // 攻撃タイプを検出
        String attackType = AttackPattern.detectAttackType(fullUrl, ATTACK_PATTERNS_PATH, NginxLogToMysql::log);

        // ホワイトリスト判定
        boolean isWhitelisted = whitelistMode && whitelistIp.equals(ipAddress);

        // 新規URL登録
        String insertSql = "INSERT INTO url_registry (method, full_url, created_at, updated_at, is_whitelisted, attack_type, server_name) " +
                          "VALUES (?, ?, NOW(), NOW(), ?, ?, ?)";
        try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
            insertStmt.setString(1, method);
            insertStmt.setString(2, fullUrl);
            insertStmt.setBoolean(3, isWhitelisted);
            insertStmt.setString(4, attackType);
            insertStmt.setString(5, serverName);

            int affectedRows = insertStmt.executeUpdate();
            if (affectedRows > 0) {
                log("新規URL登録: " + serverName + " - " + method + " " + fullUrl + " (攻撃タイプ: " + attackType + ")", "INFO");

                // サーバー情報を自動登録/更新
                Connection dbConn = dbConnect();
                if (dbConn != null) {
                    DbSchema.registerOrUpdateServer(dbConn, serverName, "自動検出されたサーバー", "", NginxLogToMysql::log);
                }
            }
        } catch (SQLException e) {
            log("URL登録エラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * アプリケーションのクリーンアップ処理
     */
    private static void cleanup() {
        log("アプリケーションをシャットダウン中...", "INFO");

        if (dbSession != null) {
            try {
                dbSession.close();
                log("データベース接続を閉じました", "INFO");
            } catch (SQLException e) {
                log("DB接続クローズ中にエラーが発生しました: " + e.getMessage(), "WARN");
            }
        }

        log("クリーンアップが完了しました", "INFO");
    }

    /**
     * 拡張ログ行を処理（サーバー名付き）
     * @param enhancedLine サーバー名付きログ行
     */
    private static void processEnhancedLogLine(String enhancedLine) {
        try {
            // サーバー名を抽出
            String serverName = "unknown";
            String originalLine = enhancedLine;

            if (enhancedLine.startsWith("[SERVER:")) {
                int endIndex = enhancedLine.indexOf("] ");
                if (endIndex > 0) {
                    serverName = enhancedLine.substring(8, endIndex);
                    originalLine = enhancedLine.substring(endIndex + 2);
                }
            }

            // ログ行をパース（HTTPリクエスト行のみ）
            Map<String, Object> logData = LogParser.parseLogLine(originalLine, NginxLogToMysql::log);
            if (logData == null) {
                return; // パースできない行はスキップ
            }

            // サーバー名をログデータに追加
            logData.put("server_name", serverName);

            // ModSecurity詳細行の場合は保留として処理
            if (ModSecHandler.detectModsecBlock(originalLine)) {
                pendingModSecLine = originalLine;
                hasPendingModSecAlert = true;
                log("ModSecurity行として処理済み (" + serverName + "): " + originalLine.substring(0, Math.min(100, originalLine.length())), "DEBUG");
                return;
            }

            Connection conn = dbConnect();
            if (conn == null) {
                log("DB接続が利用できません。ログ行をスキップします。", "ERROR");
                return;
            }

            // 直前にModSecurity: Access denied行があればblocked扱い
            boolean isModSecBlocked = hasPendingModSecAlert;
            logData.put("blocked_by_modsec", isModSecBlocked);

            // アクセスログをDBに保存
            long logId = saveAccessLog(conn, logData);
            if (logId > 0) {
                // URL登録処理
                registerUrl(conn, logData);

                // ModSecurityアラートがあれば保存
                if (isModSecBlocked && pendingModSecLine != null) {
                    List<Map<String, String>> alerts = ModSecHandler.parseModsecAlert(pendingModSecLine, NginxLogToMysql::log);
                    if (ModSecHandler.saveModsecAlertsWithServerName(conn, logId, alerts, serverName, NginxLogToMysql::log)) {
                        log("ModSecurityアラート保存完了 (" + serverName + ", アクセスログID: " + logId + ")", "DEBUG");
                    }
                }

                log("ログ処理完了 (" + serverName + "): " + logData.get("method") + " " + logData.get("full_url") +
                    " (ID: " + logId + ", Blocked: " + isModSecBlocked + ")", "DEBUG");
            }

            // 処理完了後、保留中のModSec情報をクリア
            if (hasPendingModSecAlert) {
                hasPendingModSecAlert = false;
                pendingModSecLine = null;
            }

        } catch (Exception e) {
            log("拡張ログ行処理エラー: " + e.getMessage(), "ERROR");
            // エラー時も保留中のModSec情報をクリア
            if (hasPendingModSecAlert) {
                hasPendingModSecAlert = false;
                pendingModSecLine = null;
            }
        }
    }

    /**
     * 複数サーバー監視のメインループ
     */
    private static void startMultiServerMonitoring() {
        log("複数サーバー監視を開始します...", "INFO");

        // 各LogMonitorを開始
        int startedCount = 0;
        for (Map.Entry<String, LogMonitor> entry : logMonitors.entrySet()) {
            String serverName = entry.getKey();
            LogMonitor monitor = entry.getValue();

            if (monitor.startMonitoring()) {
                startedCount++;
                log("サーバー監視開始: " + serverName, "INFO");
            } else {
                log("サーバー監視開始失敗: " + serverName, "ERROR");
            }
        }

        log(String.format("監視開始完了: %d/%d サーバー", startedCount, logMonitors.size()), "INFO");

        // メイン監視ループ（定期チェック用）
        while (isRunning.get()) {
            try {
                // 攻撃パターンファイルの定期更新チェック
                checkAttackPatternsUpdate();

                // サーバー設定ファイルの定期更新チェック
                checkServersConfigUpdate();

                // 監視状況の確認
                checkMonitorHealth();

                // 5秒間隔で監視
                Thread.sleep(5000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log("監視ループエラー: " + e.getMessage(), "ERROR");
            }
        }
    }

    /**
     * LogMonitorの健全性チェック
     */
    private static void checkMonitorHealth() {
        for (Map.Entry<String, LogMonitor> entry : logMonitors.entrySet()) {
            String serverName = entry.getKey();
            LogMonitor monitor = entry.getValue();

            if (!monitor.isMonitoring()) {
                log("サーバー監視が停止しています: " + serverName + " - 再起動を試行", "WARN");

                // 監視の再起動を試行
                if (monitor.startMonitoring()) {
                    log("サーバー監視再起動成功: " + serverName, "INFO");
                } else {
                    log("サーバー監視再起動失敗: " + serverName, "ERROR");
                }
            }
        }
    }

    /**
     * 監視統計情報を表示
     */
    private static void displayMonitoringStatistics() {
        log("=== 監視統計情報 ===", "INFO");

        int activeMonitors = 0;
        int totalMonitors = logMonitors.size();

        for (Map.Entry<String, LogMonitor> entry : logMonitors.entrySet()) {
            String serverName = entry.getKey();
            LogMonitor monitor = entry.getValue();

            if (monitor.isMonitoring()) {
                activeMonitors++;
                log("✓ " + serverName + ": 監視中", "INFO");
            } else {
                log("✗ " + serverName + ": 停止中", "WARN");
            }

            log("  " + monitor.getStatistics(), "DEBUG");
        }

        log(String.format("監視状況: %d/%d サーバーが動作中", activeMonitors, totalMonitors), "INFO");
        log("==================", "INFO");
    }

    /**
     * メインメソッド
     * @param args コマンドライン引数
     */
    public static void main(String[] args) {
        // シャットダウンフックを登録
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            isRunning.set(false);

            // 全LogMonitorを停止
            log("全サーバー監視を停止中...", "INFO");
            for (Map.Entry<String, LogMonitor> entry : logMonitors.entrySet()) {
                String serverName = entry.getKey();
                LogMonitor monitor = entry.getValue();
                monitor.stopMonitoring();
                log("サーバー監視停止: " + serverName, "INFO");
            }

            cleanup();
        }));

        try {
            // 初期化処理
            if (!initializeApplication()) {
                System.exit(1);
            }

            // 複数サーバー監視を開始
            log("複数サーバー監視モードで開始します", "INFO");
            displayMonitoringStatistics();
            startMultiServerMonitoring();

        } catch (Exception e) {
            log("予期しないエラーが発生しました: " + e.getMessage(), "CRITICAL");
            log("エラーの詳細: " + e.getClass().getSimpleName() + " - " + e.getMessage(), "ERROR");
            if (e.getCause() != null) {
                log("原因: " + e.getCause().getMessage(), "ERROR");
            }
            System.exit(1);
        }
    }
}
