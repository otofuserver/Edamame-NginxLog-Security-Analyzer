package com.edamame.agent;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.log.LogCollector;
import com.edamame.agent.network.LogTransmitter;
import com.edamame.agent.system.IptablesManager;
import com.edamame.agent.util.AgentLogger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Edamame Security Analyzer Agent
 *
 * ホスト上またはDockerコンテナ上のNginxログを収集し、
 * 枝豆コンテナへ転送、iptables操作、サーバー登録管理を行う
 * Java 11-21対応
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class EdamameAgent {

    private static final String AGENT_VERSION = "1.0.0";

    private final AgentConfig config;
    private final ScheduledExecutorService executor;

    private LogCollector logCollector;
    private LogTransmitter logTransmitter;
    private IptablesManager iptablesManager = null;

    private volatile boolean running = false;
    private String registrationId = null;

    /**
     * エージェントインスタンスを初期化
     *
     * @param configPath 設定ファイルパス
     */
    public EdamameAgent(String configPath) {
        this.config = new AgentConfig(configPath);
        // LogCollectorとLogTransmitterの初期化は設定読み込み後に移動
        this.logCollector = null; // 初期化を遅延
        this.logTransmitter = null; // 初期化を遅延
        this.executor = Executors.newScheduledThreadPool(4);

        AgentLogger.debug("Edamame Agent v" + AGENT_VERSION + " を初期化しました");
    }

    /**
     * エージェントを起動
     */
    public void start() {
        if (running) {
            AgentLogger.warn("エージェントは既に実行中です");
            return;
        }

        try {
            // 設定ファイル読み込み
            if (!config.load()) {
                AgentLogger.error("設定ファイルの読み込みに失敗しました");
                return;
            }

            // サーバー接続状態に関係なくエージェントを起動
            AgentLogger.debug("Edamameエージェントを開始します（サーバー接続は自動管理）");

            // LogCollectorとLogTransmitterを初期化（設定読み込み後）
            logCollector = new LogCollector(config);
            logTransmitter = new LogTransmitter(config);

            // IptablesManagerを初期化（LogTransmitter初期化後に移動）
            iptablesManager = new IptablesManager(config, logTransmitter);

            // 再接続時の登録ID更新コールバックを設定
            logTransmitter.setReconnectionSuccessCallback(() -> {
                // 再接続時に新しい登録IDを設定する処理は、LogTransmitter内で既に新しいIDで登録済み
                AgentLogger.info("再接続が完了しました。新しいセッションで動作を継続します");
            });

            // エージェント起動完了をマーク
            running = true;
            AgentLogger.info("Edamameエージェントが正常に開始されました");

            // 初期サーバー接続を試行（失敗時は再接続モードに移行）
            executor.schedule(this::initialServerConnection, 2, TimeUnit.SECONDS);

            // ログ収集開始（10秒後に開始して初期接続処理と分離）
            executor.scheduleWithFixedDelay(
                this::collectAndTransmitLogs,
                10,
                config.getLogCollectionInterval(),
                TimeUnit.SECONDS
            );

            // iptables管理開始（20秒後に開始してログ収集と確実に分離）
            executor.scheduleWithFixedDelay(
                this::manageIptables,
                20,
                config.getIptablesCheckInterval(),
                TimeUnit.SECONDS
            );

            // ハートビート送信（30秒後に開始して他タスクと完全に分離）
            executor.scheduleWithFixedDelay(
                this::sendHeartbeat,
                30,
                config.getHeartbeatInterval(),
                TimeUnit.SECONDS
            );

        } catch (Exception e) {
            AgentLogger.error("エージェント起動中にエラーが発生しました: " + e.getMessage());
            stop();
        }
    }

    /**
     * 初期サーバー接続処理
     */
    private void initialServerConnection() {
        try {
            AgentLogger.info("サーバーへの初期接続を試行します...");

            // 接続テスト（軽量）
            if (config.testTcpConnection()) {
                AgentLogger.info("サーバー接続テストに成功しました");

                // サーバー登録を試行
                registrationId = logTransmitter.registerServer();
                if (registrationId != null) {
                    // LogTransmitter側でログ出力されるため、ここでの重複ログは削除
                    return; // 成功時は通常動作
                } else {
                    AgentLogger.warn("サーバー登録に失敗しました。再接続待機モードに移行します");
                }
            } else {
                AgentLogger.warn("サーバー接続テストに失敗しました。再接続待機モードに移行します");
            }

            // 接続失敗時は再接続待機モードに移行
            logTransmitter.triggerReconnectMode();

        } catch (Exception e) {
            AgentLogger.warn("初期サーバー接続中にエラーが発生しました: " + e.getMessage() + "。再接続待機モードに移行します");
            logTransmitter.triggerReconnectMode();
        }
    }

    /**
     * ログ収集・転送処理
     */
    private void collectAndTransmitLogs() {
        try {
            var logs = logCollector.collectNewLogs();
            if (!logs.isEmpty()) {
                boolean success = logTransmitter.transmitLogs(logs);
                if (success) {
                    AgentLogger.info(logs.size() + " 件のログエントリを転送しました");
                } else {
                    AgentLogger.warn("ログ転送に失敗しました");
                }
            }
        } catch (Exception e) {
            AgentLogger.error("ログ収集・転送中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * iptables管理処理
     */
    private void manageIptables() {
        try {
            iptablesManager.processBlockRequests();
        } catch (Exception e) {
            AgentLogger.error("iptables管理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * ハートビート送信処理
     */
    private void sendHeartbeat() {
        try {
            logTransmitter.sendHeartbeat();
        } catch (Exception e) {
            AgentLogger.error("ハートビート送信中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * エージェントを停止
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        try {
            // スケジュールされたタスクを停止
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // サーバー登録を解除
            if (registrationId != null) {
                logTransmitter.unregisterServer(registrationId);
            }

            // LogTransmitterのクリーンアップ（再接続タスクとキューを含む）
            logTransmitter.cleanup();

            AgentLogger.info("Edamameエージェントを停止しました");

        } catch (Exception e) {
            AgentLogger.error("エージェント停止中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * シャットダウンフックを設定
     */
    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            AgentLogger.info("シャットダウンシグナルを受信しました");
            stop();
        }));
    }

    /**
     * メインエントリーポイント
     */
    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "agent-config.json";

        AgentLogger.info("Edamame Agent v" + AGENT_VERSION + " を開始しています...");
        AgentLogger.info("設定ファイルを使用: " + configPath);

        EdamameAgent agent = new EdamameAgent(configPath);
        agent.setupShutdownHook();
        agent.start();

        // メインスレッドを維持
        try {
            while (agent.running) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            AgentLogger.info("メインスレッドが中断されました");
            agent.stop();
        }
    }
}
