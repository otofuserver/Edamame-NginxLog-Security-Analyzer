package com.edamame.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * サーバー設定管理クラス
 * 複数サーバーの監視設定を管理
 */
public class ServerConfig {

    private final String configPath;
    private final BiConsumer<String, String> logFunction;
    private final List<ServerInfo> servers = new ArrayList<>();
    private long lastModified = 0;

    /**
     * サーバー情報を表すレコードクラス
     * @param name サーバー名
     * @param logPath ログファイルパス
     */
    public record ServerInfo(String name, String logPath) {
        /**
         * ログファイルが存在するかチェック
         * @return 存在する場合true
         */
        public boolean logFileExists() {
            return Files.exists(Paths.get(logPath));
        }
    }

    /**
     * コンストラクタ
     * @param configPath 設定ファイルパス
     * @param logFunction ログ出力関数
     */
    public ServerConfig(String configPath, BiConsumer<String, String> logFunction) {
        this.configPath = configPath;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
    }

    /**
     * デフォルト設定でインスタンスを作成
     * @param logFunction ログ出力関数
     * @return ServerConfigインスタンス
     */
    public static ServerConfig createDefault(BiConsumer<String, String> logFunction) {
        String configPath = System.getenv("SERVERS_CONFIG_PATH");
        if (configPath == null || configPath.trim().isEmpty()) {
            configPath = "/app/config/servers.conf";
        }
        return new ServerConfig(configPath, logFunction);
    }

    /**
     * サーバー設定を読み込み
     * @return 読み込み成功可否
     */
    public boolean loadServers() {
        try {
            Path path = Paths.get(configPath);
            if (!Files.exists(path)) {
                logFunction.accept("サーバー設定ファイルが見つかりません: " + configPath, "WARN");
                return createDefaultConfig();
            }

            List<String> lines = Files.readAllLines(path);
            servers.clear();

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // 空行とコメント行をスキップ
                }

                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    String serverName = parts[0].trim();
                    String logPath = parts[1].trim();
                    servers.add(new ServerInfo(serverName, logPath));
                } else {
                    logFunction.accept("無効な設定行をスキップ: " + line, "WARN");
                }
            }

            lastModified = Files.getLastModifiedTime(path).toMillis();
            logFunction.accept("サーバー設定読み込み完了: " + servers.size() + "台", "INFO");
            return true;

        } catch (IOException e) {
            logFunction.accept("サーバー設定読み込みエラー: " + e.getMessage(), "ERROR");
            return createDefaultConfig();
        }
    }

    /**
     * デフォルト設定を作成
     * @return 作成成功可否
     */
    private boolean createDefaultConfig() {
        try {
            logFunction.accept("デフォルトサーバー設定を作成します", "INFO");

            // デフォルトサーバーを追加
            servers.clear();
            servers.add(new ServerInfo("default", "/var/log/nginx/nginx.log"));

            // 設定ファイルを作成
            String defaultContent = """
                # Edamame NginxLog Security Analyzer - サーバー設定
                # 形式: サーバー名,ログファイルパス
                
                default,/var/log/nginx/nginx.log
                """;

            Path path = Paths.get(configPath);
            Path parentDir = path.getParent();
            if (parentDir != null && !Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
            }

            Files.writeString(path, defaultContent);
            lastModified = Files.getLastModifiedTime(path).toMillis();

            logFunction.accept("デフォルトサーバー設定ファイルを作成しました: " + configPath, "INFO");
            return true;

        } catch (IOException e) {
            logFunction.accept("デフォルト設定作成エラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * 設定ファイルの更新チェック
     * @param configPath 設定ファイルパス
     * @param logFunction ログ出力関数
     * @return 更新があった場合true
     */
    public static boolean updateIfNeeded(String configPath, BiConsumer<String, String> logFunction) {
        try {
            Path path = Paths.get(configPath);
            if (!Files.exists(path)) {
                return false;
            }

            long currentModified = Files.getLastModifiedTime(path).toMillis();
            // この実装では簡易的な更新チェックのみ
            return false; // 実際の更新処理は将来実装

        } catch (IOException e) {
            if (logFunction != null) {
                logFunction.accept("設定ファイル更新チェックエラー: " + e.getMessage(), "ERROR");
            }
            return false;
        }
    }

    /**
     * サーバーリストを取得
     * @return サーバー情報のリスト
     */
    public List<ServerInfo> getServers() {
        return new ArrayList<>(servers);
    }

    /**
     * ログファイルの存在確認
     * @return 存在しないファイルを持つサーバーのリスト
     */
    public List<ServerInfo> validateLogFiles() {
        List<ServerInfo> missingFiles = new ArrayList<>();

        for (ServerInfo server : servers) {
            if (!server.logFileExists()) {
                missingFiles.add(server);
                logFunction.accept("ログファイルが見つかりません: " + server.name() + " → " + server.logPath(), "WARN");
            }
        }

        return missingFiles;
    }

    /**
     * 設定概要を表示
     */
    public void displayConfigSummary() {
        logFunction.accept("=== サーバー設定概要 ===", "INFO");
        logFunction.accept("設定ファイル: " + configPath, "INFO");
        logFunction.accept("サーバー数: " + servers.size(), "INFO");

        for (ServerInfo server : servers) {
            String status = server.logFileExists() ? "✓" : "✗";
            logFunction.accept(String.format("  %s %s → %s", status, server.name(), server.logPath()), "INFO");
        }

        logFunction.accept("最終更新: " + formatLastModified(), "INFO");
        logFunction.accept("======================", "INFO");
    }

    /**
     * 最終更新時刻をフォーマット
     * @return フォーマット済み時刻文字列
     */
    private String formatLastModified() {
        if (lastModified == 0) {
            return "未設定";
        }
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(lastModified),
            java.time.ZoneId.systemDefault()
        );
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     * サーバー名でサーバー情報を検索
     * @param serverName サーバー名
     * @return サーバー情報（見つからない場合null）
     */
    public ServerInfo findServerByName(String serverName) {
        return servers.stream()
            .filter(server -> server.name().equals(serverName))
            .findFirst()
            .orElse(null);
    }

    /**
     * サーバー設定を追加
     * @param serverName サーバー名
     * @param logPath ログファイルパス
     */
    public void addServer(String serverName, String logPath) {
        servers.add(new ServerInfo(serverName, logPath));
        logFunction.accept("サーバー追加: " + serverName + " → " + logPath, "INFO");
    }

    /**
     * サーバー数を取得
     * @return サーバー数
     */
    public int getServerCount() {
        return servers.size();
    }

    /**
     * 設定が空かどうかを確認
     * @return 空の場合true
     */
    public boolean isEmpty() {
        return servers.isEmpty();
    }
}
