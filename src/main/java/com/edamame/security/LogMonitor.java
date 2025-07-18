package com.edamame.security;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 個別サーバーのログファイル監視を担当するクラス
 * 各サーバーのログファイルを独立してtail -fのように監視する
 */
public class LogMonitor {

    private final ServerConfig.ServerInfo serverInfo;
    private final BiConsumer<String, String> logger;
    private final Consumer<String> logLineProcessor;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private Thread monitorThread;
    private long lastFileSize = 0;
    private long lastModified = 0;

    /**
     * コンストラクタ
     * @param serverInfo 監視対象サーバー情報
     * @param logger ログ出力関数
     * @param logLineProcessor ログ行処理関数
     */
    public LogMonitor(ServerConfig.ServerInfo serverInfo,
                     BiConsumer<String, String> logger,
                     Consumer<String> logLineProcessor) {
        this.serverInfo = serverInfo;
        this.logger = logger;
        this.logLineProcessor = logLineProcessor;
    }

    /**
     * ログ監視を開始
     * @return 開始成功可否
     */
    public boolean startMonitoring() {
        if (isRunning.get()) {
            logger.accept("サーバー " + serverInfo.name() + " は既に監視中です", "WARN");
            return false;
        }

        Path logPath = Paths.get(serverInfo.logPath());
        if (!Files.exists(logPath)) {
            logger.accept("ログファイルが見つかりません: " + serverInfo.name() + " → " + serverInfo.logPath(), "ERROR");
            return false;
        }

        try {
            // ファイルサイズと更新時刻を初期化
            lastFileSize = Files.size(logPath);
            lastModified = Files.getLastModifiedTime(logPath).toMillis();

            isRunning.set(true);

            // 監視スレッドを開始
            monitorThread = new Thread(this::monitorLoop, "LogMonitor-" + serverInfo.name());
            monitorThread.setDaemon(true);
            monitorThread.start();

            logger.accept("ログ監視開始: " + serverInfo.name() + " → " + serverInfo.logPath(), "INFO");
            return true;

        } catch (IOException e) {
            logger.accept("ログ監視開始エラー (" + serverInfo.name() + "): " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * ログ監視を停止
     */
    public void stopMonitoring() {
        if (!isRunning.get()) {
            return;
        }

        isRunning.set(false);

        if (monitorThread != null) {
            monitorThread.interrupt();
            try {
                monitorThread.join(5000); // 最大5秒待機
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        logger.accept("ログ監視停止: " + serverInfo.name(), "INFO");
    }

    /**
     * 監視状態を確認
     * @return 監視中かどうか
     */
    public boolean isMonitoring() {
        return isRunning.get() && monitorThread != null && monitorThread.isAlive();
    }

    /**
     * サーバー情報を取得
     * @return サーバー情報
     */
    public ServerConfig.ServerInfo getServerInfo() {
        return serverInfo;
    }

    /**
     * ログ監視のメインループ
     */
    private void monitorLoop() {
        Path logPath = Paths.get(serverInfo.logPath());

        try (BufferedReader reader = Files.newBufferedReader(logPath)) {
            // ファイルの末尾にシーク（tail -f のような動作）
            reader.skip(lastFileSize);

            String line;
            while (isRunning.get()) {
                try {
                    // ファイルローテーション検知
                    if (checkFileRotation(logPath)) {
                        logger.accept("ログローテーション検知: " + serverInfo.name(), "INFO");
                        break; // ループを抜けて再開
                    }

                    line = reader.readLine();
                    if (line != null) {
                        // ログ行を処理（サーバー名を含めて処理）
                        processLogLineWithServerInfo(line);
                    } else {
                        // 新しい行がない場合は短時間待機
                        Thread.sleep(100);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    logger.accept("ログ読み込みエラー (" + serverInfo.name() + "): " + e.getMessage(), "ERROR");
                    // エラー時は1秒待機してリトライ
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

        } catch (IOException e) {
            logger.accept("ログファイルオープンエラー (" + serverInfo.name() + "): " + e.getMessage(), "ERROR");
        }

        // ファイルローテーション後またはエラー後の再開処理
        if (isRunning.get()) {
            try {
                Thread.sleep(1000); // 1秒待機
                monitorLoop(); // 再帰呼び出しで監視を再開
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * ファイルローテーションを検知
     * @param logPath ログファイルパス
     * @return ローテーション検知結果
     */
    private boolean checkFileRotation(Path logPath) {
        try {
            if (!Files.exists(logPath)) {
                return true; // ファイルが存在しない場合はローテーション
            }

            long currentSize = Files.size(logPath);
            long currentModified = Files.getLastModifiedTime(logPath).toMillis();

            // ファイルサイズが小さくなった場合（ローテーション）
            if (currentSize < lastFileSize) {
                lastFileSize = currentSize;
                lastModified = currentModified;
                return true;
            }

            // ファイルサイズと更新時刻を更新
            lastFileSize = currentSize;
            lastModified = currentModified;

            return false;

        } catch (IOException e) {
            logger.accept("ファイルローテーション検知エラー (" + serverInfo.name() + "): " + e.getMessage(), "WARN");
            return false;
        }
    }

    /**
     * ログ行をサーバー情報付きで処理
     * @param line ログ行
     */
    private void processLogLineWithServerInfo(String line) {
        try {
            // サーバー名を含むログ行として処理関数に渡す
            String enhancedLine = "[SERVER:" + serverInfo.name() + "] " + line;
            logLineProcessor.accept(enhancedLine);

        } catch (Exception e) {
            logger.accept("ログ行処理エラー (" + serverInfo.name() + "): " + e.getMessage(), "ERROR");
        }
    }

    /**
     * 監視統計情報を取得
     * @return 統計情報の文字列
     */
    public String getStatistics() {
        return String.format("LogMonitor[%s] - Running: %s, Thread: %s, FileSize: %d",
            serverInfo.name(),
            isRunning.get(),
            monitorThread != null ? monitorThread.isAlive() : false,
            lastFileSize);
    }

    @Override
    public String toString() {
        return String.format("LogMonitor{server='%s', logPath='%s', running=%s}",
            serverInfo.name(), serverInfo.logPath(), isRunning.get());
    }
}
