package com.edamame.security;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * 複数サーバーの設定を管理するクラス
 * servers.confファイルからサーバー情報を読み込み、監視対象を管理する
 */
public class ServerConfig {

    /**
     * サーバー情報を格納するrecordクラス
     * @param name サーバー管理名
     * @param logPath ログファイルパス
     */
    public record ServerInfo(String name, String logPath) {

        /**
         * サーバー情報の妥当性をチェック
         * @return 妥当性チェック結果
         */
        public boolean isValid() {
            return name != null && !name.trim().isEmpty() &&
                   logPath != null && !logPath.trim().isEmpty();
        }

        /**
         * ログファイルの存在確認
         * @return ファイル存在確認結果
         */
        public boolean logFileExists() {
            return Files.exists(Paths.get(logPath));
        }

        @Override
        public String toString() {
            return String.format("ServerInfo[name='%s', logPath='%s']", name, logPath);
        }
    }

    private static final String DEFAULT_CONFIG_PATH = "/app/config/servers.conf";
    private static long staticLastModified = 0; // 静的メソッド用の最終更新時刻管理
    
    private final String configPath;
    private final BiConsumer<String, String> logger;
    private final List<ServerInfo> servers = new ArrayList<>();
    private long lastModified = 0;

    /**
     * コンストラクタ
     * @param configPath 設定ファイルパス
     * @param logger ログ出力関数
     */
    public ServerConfig(String configPath, BiConsumer<String, String> logger) {
        this.configPath = configPath != null ? configPath : DEFAULT_CONFIG_PATH;
        this.logger = logger;
    }

    /**
     * デフォルトパスでServerConfigを作成
     * @param logger ログ出力関数
     * @return ServerConfigインスタンス
     */
    public static ServerConfig createDefault(BiConsumer<String, String> logger) {
        return new ServerConfig(DEFAULT_CONFIG_PATH, logger);
    }

    /**
     * 設定ファイルからサーバーリストを読み込み
     * @return 読み込み成功可否
     */
    public boolean loadServers() {
        Path configFile = Paths.get(configPath);

        if (!Files.exists(configFile)) {
            logger.accept("サーバー設定ファイルが見つかりません: " + configPath, "WARN");
            return false;
        }

        try {
            long currentModified = Files.getLastModifiedTime(configFile).toMillis();

            // ファイルが更新されていない場合はスキップ
            if (currentModified == lastModified && !servers.isEmpty()) {
                return true;
            }

            servers.clear();
            List<String> lines = Files.readAllLines(configFile);
            int lineNumber = 0;
            Set<String> serverNames = new HashSet<>(); // 重複チェック用

            for (String line : lines) {
                lineNumber++;
                line = line.trim();

                // コメント行や空行をスキップ
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // CSV形式で解析: 管理名,ログパス
                String[] parts = line.split(",", 2);
                if (parts.length != 2) {
                    logger.accept(String.format("設定ファイル %d行目の形式が不正です: %s",
                        lineNumber, line), "WARN");
                    continue;
                }

                String name = parts[0].trim();
                String logPath = parts[1].trim();

                // 管理名の重複チェック
                if (serverNames.contains(name)) {
                    logger.accept(String.format("致命的エラー: サーバー管理名が重複しています - '%s' (%d行目)",
                        name, lineNumber), "ERROR");
                    logger.accept("各サーバーには一意な管理名を設定してください", "ERROR");
                    logger.accept("設定ファイル: " + configPath, "ERROR");
                    return false; // 重複エラーで終了
                }

                ServerInfo server = new ServerInfo(name, logPath);
                if (server.isValid()) {
                    servers.add(server);
                    serverNames.add(name); // 重複チェック用セットに追加
                    logger.accept(String.format("サーバー設定を読み込み: %s → %s",
                        name, logPath), "DEBUG");
                } else {
                    logger.accept(String.format("無効なサーバー設定をスキップ: %s", line), "WARN");
                }
            }

            lastModified = currentModified;
            logger.accept(String.format("サーバー設定読み込み完了: %d台のサーバーを登録",
                servers.size()), "INFO");

            return !servers.isEmpty();

        } catch (IOException e) {
            logger.accept("サーバー設定ファイル読み込みエラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * 読み込み済みサーバーリストを取得
     * @return サーバー情報のリスト（読み取り専用）
     */
    public List<ServerInfo> getServers() {
        return Collections.unmodifiableList(servers);
    }

    /**
     * 指定名のサーバー情報を取得
     * @param name サーバー管理名
     * @return サーバー情報（見つからない場合はnull）
     */
    public ServerInfo getServer(String name) {
        return servers.stream()
            .filter(server -> server.name().equals(name))
            .findFirst()
            .orElse(null);
    }

    /**
     * 設定ファイルが更新されているかチェック
     * @return 更新確認結果
     */
    public boolean isConfigUpdated() {
        Path configFile = Paths.get(configPath);
        if (!Files.exists(configFile)) {
            return false;
        }

        try {
            long currentModified = Files.getLastModifiedTime(configFile).toMillis();
            return currentModified != lastModified;
        } catch (IOException e) {
            logger.accept("設定ファイル更新確認エラー: " + e.getMessage(), "WARN");
            return false;
        }
    }

    /**
     * ログファイルの存在確認を実行
     * @return 存在しないログファイルのリスト
     */
    public List<ServerInfo> validateLogFiles() {
        List<ServerInfo> missingFiles = new ArrayList<>();

        for (ServerInfo server : servers) {
            if (!server.logFileExists()) {
                missingFiles.add(server);
                logger.accept(String.format("ログファイルが見つかりません: %s (%s)",
                    server.name(), server.logPath()), "WARN");
            }
        }

        return missingFiles;
    }

    /**
     * 設定情報の概要を表示
     */
    public void displayConfigSummary() {
        logger.accept("=== サーバー設定概要 ===", "INFO");
        logger.accept(String.format("設定ファイル: %s", configPath), "INFO");
        logger.accept(String.format("登録サーバー数: %d", servers.size()), "INFO");

        for (ServerInfo server : servers) {
            String status = server.logFileExists() ? "✓" : "✗";
            logger.accept(String.format("  %s %s → %s", status, server.name(), server.logPath()), "INFO");
        }
        logger.accept("========================", "INFO");
    }

    /**
     * 設定ファイルが更新されているかチェックして、必要に応じて再読み込み
     * @param configPath 設定ファイルパス
     * @param logger ログ出力関数
     * @return 更新があった場合true
     */
    public static boolean updateIfNeeded(String configPath, BiConsumer<String, String> logger) {
        Path configFile = Paths.get(configPath != null ? configPath : DEFAULT_CONFIG_PATH);
        
        if (!Files.exists(configFile)) {
            return false;
        }

        try {
            long currentModified = Files.getLastModifiedTime(configFile).toMillis();
            
            // 初回チェック時は現在の時刻を記録してfalseを返す（ログ出力なし）
            if (staticLastModified == 0) {
                staticLastModified = currentModified;
                return false;
            }
            
            // ファイルが実際に更新されているかチェック
            if (currentModified > staticLastModified) {
                logger.accept("サーバー設定ファイルの更新を検知しました", "INFO");
                staticLastModified = currentModified; // 更新時刻を記録
                
                // 新しいインスタンスで設定を再読み込み
                ServerConfig config = new ServerConfig(configPath, logger);
                boolean loadResult = config.loadServers();

                if (loadResult) {
                    logger.accept("サーバー設定の再読み込みが完了しました", "INFO");
                } else {
                    logger.accept("サーバー設定の再読み込みに失敗しました", "WARN");
                }

                return loadResult;
            }
            
            return false; // 更新なし（ログ出力なし）

        } catch (IOException e) {
            logger.accept("設定ファイル更新確認エラー: " + e.getMessage(), "WARN");
            return false;
        }
    }

    /**
     * サーバー設定をロードする静的メソッド（互換性のため）
     * @param configPath 設定ファイルパス
     * @param logger ログ出力関数
     * @return 読み込み成��可否
     * @deprecated インスタンスメソッドのloadServers()を使用してください
     */
    @Deprecated
    public static boolean loadServerConfigs(String configPath, BiConsumer<String, String> logger) {
        ServerConfig config = new ServerConfig(configPath, logger);
        return config.loadServers();
    }
}
