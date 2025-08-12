package com.edamame.agent.config;

import com.edamame.agent.util.AgentLogger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.io.DataOutputStream;
import java.io.DataInputStream;

/**
 * エージェント設定管理クラス
 * JSON設定ファイルの読み込み・管理を行う
 * Java 11-21対応
 * サーバー管理設定の形式:
 * "servers": [
 *   "# 例: メインNGINXサーバー",
 *   "main-nginx,/var/log/nginx/nginx.log",
 *   "",
 *   "# 例: APIサーバー", 
 *   "# api-server,/var/log/nginx/api.log"
 * ]
 *
 * @author Edamame Team
 * @version 1.2.0
 */
public class AgentConfig {

    // 設定ファイルパス
    private String configPath;
    private final ObjectMapper objectMapper;

    // エージェント情報
    private String agentName = "edamame-agent-default";
    private String agentDescription = "Edamame Security Agent";
    private String agentIpAddress = "auto";       // 追加: エージェントIPアドレス（"auto"で自動取得）

    // Edamameコンテナ接続情報（Agent専用通信）
    private String edamameHost = "localhost";
    private int edamamePort = 2591;                // Agent専用ポート
    private String protocol = "tcp";               // TCP通信
    private String apiKey = "edamame-agent-api-key-2025";
    private boolean useSSL = false;
    private int connectionTimeout = 30;
    private boolean socketKeepAlive = true;

    // サーバー情報
    private String serverName = "default-server";

    // ログ収集設定
    private List<String> nginxLogPaths = new ArrayList<>();
    private Map<String, List<String>> serverLogMappings = new HashMap<>(); // サーバー名 -> ログパスリストのマッピング
    private String defaultServerName = "main-nginx"; // デフォルトサーバー名
    private int logCollectionInterval = 10;       // 秒
    private String logFormat = "combined";
    private int maxBatchSize = 100;

    // iptables設定
    private boolean enableIptables = true;
    private int iptablesCheckInterval = 30;       // 秒
    private String iptablesChain = "INPUT";
    private int blockDuration = 3600;             // 秒

    // ハートビート設定
    private int heartbeatInterval = 60;           // 秒

    /**
     * コンストラクタ
     *
     * @param configPath 設定ファイルパス
     */
    public AgentConfig(String configPath) {
        this.configPath = configPath;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 設定ファイルを読み込み
     *
     * @return 読み込み成功の可否
     */
    public boolean load() {
        try {
            if (!Files.exists(Paths.get(configPath))) {
                AgentLogger.warn("設定ファイルが見つかりません: " + configPath + "、デフォルト設定を使用します");
                createDefaultConfig();
                return true;
            }

            String jsonContent = Files.readString(Paths.get(configPath));
            JsonNode root = objectMapper.readTree(jsonContent);

            loadAgentConfig(root);
            loadEdamameConfig(root);
            loadLoggingConfig(root);
            loadIptablesConfig(root);
            loadHeartbeatConfig(root);  // 追加: heartbeat設定の読み込み
            loadAdvancedConfig(root);  // 追加: advanced設定の読み込み

            parseServerConfig();
            AgentLogger.info("設定ファイルの読み込みが完了しました: " + configPath);
            return true;

        } catch (Exception e) {
            AgentLogger.error("設定ファイルの読み込みに失敗しました", e);
            return false;
        }
    }

    /**
     * エージェント設定を読み込み
     */
    private void loadAgentConfig(JsonNode root) {
        JsonNode agentNode = root.path("agent");
        if (!agentNode.isMissingNode()) {
            agentName = agentNode.path("name").asText(agentName);
            agentDescription = agentNode.path("description").asText(agentDescription);
            agentIpAddress = agentNode.path("ipAddress").asText(agentIpAddress);
        }
    }

    /**
     * Edamame接続設定を読み込み
     */
    private void loadEdamameConfig(JsonNode root) {
        JsonNode edamameNode = root.path("edamame");
        if (!edamameNode.isMissingNode()) {
            edamameHost = edamameNode.path("host").asText(edamameHost);
            edamamePort = edamameNode.path("port").asInt(edamamePort);
            protocol = edamameNode.path("protocol").asText(protocol);
            apiKey = edamameNode.path("apiKey").asText(apiKey);
            useSSL = edamameNode.path("useSSL").asBoolean(useSSL);
            connectionTimeout = edamameNode.path("connectionTimeout").asInt(connectionTimeout);
            socketKeepAlive = edamameNode.path("socketKeepAlive").asBoolean(socketKeepAlive);
        }
    }

    /**
     * ログ収集設定を読み込み
     */
    private void loadLoggingConfig(JsonNode root) {
        JsonNode loggingNode = root.path("logging");
        if (!loggingNode.isMissingNode()) {
            logCollectionInterval = loggingNode.path("collectionInterval").asInt(logCollectionInterval);
            logFormat = loggingNode.path("format").asText(logFormat);
            maxBatchSize = loggingNode.path("maxBatchSize").asInt(maxBatchSize);

            // debugModeのみで統一（logLevelは廃止）
            boolean debugMode = loggingNode.path("debugMode").asBoolean(false);
            AgentLogger.setDebugEnabled(debugMode);
            
            if (debugMode) {
                AgentLogger.debug("デバッグモードが有効になりました");
            }

            // サーバー設定の読み込み
            JsonNode serversNode = loggingNode.path("servers");
            if (serversNode.isArray()) {
                nginxLogPaths.clear();
                serverLogMappings.clear(); // 追加: サーバー名とログパスのマッピングをクリア
                AgentLogger.debug("サーバー設定の読み込みを開始します。配列要素数: " + serversNode.size());

                for (JsonNode serverLine : serversNode) {
                    String line = serverLine.asText().trim();
                    AgentLogger.debug("処理中の設定行: '" + line + "'");

                    if (!line.isEmpty() && !line.startsWith("#")) {
                        String[] parts = line.split(",", 2);
                        AgentLogger.debug("分割結果: parts.length=" + parts.length + ", parts=" + java.util.Arrays.toString(parts));

                        if (parts.length == 2) {
                            String serverName = parts[0].trim();
                            String logPath = parts[1].trim();

                            nginxLogPaths.add(logPath);
                            serverLogMappings.computeIfAbsent(serverName, k -> new ArrayList<>()).add(logPath);

                            AgentLogger.info("サーバー設定を登録: " + serverName + " -> " + logPath);
                        } else {
                            AgentLogger.warn("無効なサーバー設定行をスキップ: " + line);
                        }
                    } else {
                        AgentLogger.debug("コメント行または空行をスキップ: " + line);
                    }
                }

                AgentLogger.info("サーバー設定の読み込み完了。登録されたマッピング数: " + serverLogMappings.size());
                AgentLogger.debug("最終的なマッピング: " + serverLogMappings);
            } else {
                AgentLogger.warn("servers設定が配列ではありません: " + serversNode.getNodeType());
            }
        }
    }

    /**
     * iptables設定を読み込み
     */
    private void loadIptablesConfig(JsonNode root) {
        JsonNode iptablesNode = root.path("iptables");
        if (!iptablesNode.isMissingNode()) {
            enableIptables = iptablesNode.path("enabled").asBoolean(enableIptables);
            iptablesCheckInterval = iptablesNode.path("checkInterval").asInt(iptablesCheckInterval);
            iptablesChain = iptablesNode.path("chain").asText(iptablesChain);
            blockDuration = iptablesNode.path("blockDuration").asInt(blockDuration);
        }
    }

    /**
     * ハートビート設定を読み込み
     */
    private void loadHeartbeatConfig(JsonNode root) {
        JsonNode heartbeatNode = root.path("heartbeat");
        if (!heartbeatNode.isMissingNode()) {
            heartbeatInterval = heartbeatNode.path("interval").asInt(heartbeatInterval);
        }
    }

    /**
     * 高度な設定を読み込み（v1.12.0で廃止、loggingセクションに統合）
     */
    private void loadAdvancedConfig(JsonNode root) {
        // 後方互換性のため、advancedセクションが存在する場合は読み込む
        JsonNode advancedNode = root.path("advanced");
        if (!advancedNode.isMissingNode()) {
            AgentLogger.warn("advancedセクションは廃止されました。loggingセクションのdebugModeを使用してください");

            // 旧設定からの移行サポート
            boolean debugMode = advancedNode.path("debugMode").asBoolean(false);
            AgentLogger.setDebugEnabled(debugMode);
            
            if (debugMode) {
                AgentLogger.debug("旧advancedセクションからデバッグモードが有効になりました");
            }
        }
    }

    /**
     * TCP接続テスト（Connection Test用特別プロトコル）
     * サーバー側で接続テストであることを認識させ、認証エラーを回避
     */
    public boolean testTcpConnection() {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(3000); // 3秒タイムアウト
            socket.connect(new InetSocketAddress(edamameHost, edamamePort), 3000);
            
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            
            // 接続テスト専用プロトコル（MSG_TYPE_CONNECTION_TEST: 0x09）
            out.writeByte(0x09); // 新しいメッセージタイプ
            out.writeInt(0);     // データ長���0
            out.flush();
            
            // レスポンスを受信
            byte responseCode = in.readByte();
            boolean testSuccess = (responseCode == 0x00); // RESPONSE_SUCCESS

            AgentLogger.info("TCP接続テストが成功しました: " + edamameHost + ":" + edamamePort);
            return testSuccess;

        } catch (Exception e) {
            AgentLogger.error("TCP接続テストに失敗しました: " + edamameHost + ":" + edamamePort, e);
            return false;
        }
    }

    /**
     * サーバー設定の解析
     */
    public void parseServerConfig() {
        int validServers = 0;
        for (String logPath : nginxLogPaths) {
            if (Files.exists(Paths.get(logPath))) {
                validServers++;
            } else {
                AgentLogger.warn("ログファイルが存在しません: " + logPath);
            }
        }
        AgentLogger.info(validServers + " 個のサーバーログ設定を解析しました");
    }

    /**
     * デフォルト設定ファイルを作成
     */
    private void createDefaultConfig() {
        String defaultConfig = """
        {
          "agent": {
            "name": "edamame-agent-01",
            "description": "Edamame Security Agent Instance 01"
          },
          "edamame": {
            "host": "localhost",
            "port": 2591,
            "protocol": "tcp",
            "apiKey": "edamame-agent-api-key-2025",
            "useSSL": false,
            "connectionTimeout": 30,
            "socketKeepAlive": true
          },
          "logging": {
            "servers": [
              "# 例: メインNGINXサーバー",
              "main-nginx,/var/log/nginx/nginx.log",
              "",
              "# 例: APIサーバー",
              "# api-server,/var/log/nginx/api.log"
            ],
            "collectionInterval": 10,
            "format": "combined",
            "maxBatchSize": 100
          },
          "iptables": {
            "enabled": false,
            "checkInterval": 30,
            "chain": "INPUT",
            "blockDuration": 3600
          }
        }
        """;

        try {
            Files.writeString(Paths.get(configPath), defaultConfig);
            AgentLogger.info("デフォルト設定ファイルを作成しました: " + configPath);
        } catch (IOException e) {
            AgentLogger.error("デフォルト設定ファイルの作成に失敗しました: " + e.getMessage());
        }
    }

    // Getter メソッド
    public String getAgentId() { return agentName; }
    public String getAgentIpAddress() { return agentIpAddress; }  // 追加: エージェントIPアドレス取得
    
    public String getEdamameHost() { return edamameHost; }
    public int getEdamamePort() { return edamamePort; }
    public String getApiKey() { return apiKey; }
    
    public String getServerName() { return serverName; }
    
    public List<String> getNginxLogPaths() { return nginxLogPaths; }
    public int getLogCollectionInterval() { return logCollectionInterval; }
    public String getLogFormat() { return logFormat; }
    public int getMaxBatchSize() { return maxBatchSize; }
    
    public boolean isEnableIptables() { return enableIptables; }
    public int getIptablesCheckInterval() { return iptablesCheckInterval; }
    public String getIptablesChain() { return iptablesChain; }
    
    public int getHeartbeatInterval() { return heartbeatInterval; }
    
    /**
     * サーバー名とログパスのマッピングを取得
     * @return サーバー名 -> ログパスのマップ
     */
    public Map<String, List<String>> getServerLogMappings() { return serverLogMappings; }
    
    /**
     * ログパスからサーバー名を取得
     * @param logPath ログファイルパス
     * @return サーバー名（見つからない場合はデフォルトサーバー名）
     */
    public String getServerNameByLogPath(String logPath) {
        AgentLogger.debug("サーバー名を検索中: logPath='" + logPath + "'");
        AgentLogger.debug("利用可能なマッピング: " + serverLogMappings);
        
        for (Map.Entry<String, List<String>> entry : serverLogMappings.entrySet()) {
            if (entry.getValue().contains(logPath)) {
                AgentLogger.debug("マッチしました: " + entry.getKey() + " -> " + logPath);
                return entry.getKey();
            }
        }
        
        // 設定から見つからない場合はデフォルトサーバー名を返す
        AgentLogger.info("ログパス " + logPath + " にはデフォルトサーバー名を使用: " + defaultServerName);
        return defaultServerName;
    }

}