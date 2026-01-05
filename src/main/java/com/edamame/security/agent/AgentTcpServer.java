package com.edamame.security.agent;

import com.edamame.security.*;
import static com.edamame.security.db.DbService.*;
import com.edamame.security.modsecurity.ModSecurityQueue;
import com.edamame.security.modsecurity.ModSecHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import static com.edamame.agent.network.TcpProtocolConstants.*;

import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import com.edamame.security.tools.AppLogger;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.edamame.security.tools.UrlCodec;

/**
 * エージェントTCP通信サーバー
 * ポート2591でエージェントからのTCP接続を受け付け、
 * カスタムバイナリプロトコルで通信を行う
 * v2.0.0: DbService/DbSessionパターンに完全移行
 * v3.0.0: ModSecurityキュー管理をNginxLogToMysqlに移行
 *
 * @author Edamame Team
 * @version 3.0.0
 */
public class AgentTcpServer {

    // TCP通信設定
    private static final int DEFAULT_PORT = 2591;
    private static final int THREAD_POOL_SIZE = 10;
    private static final int SOCKET_TIMEOUT = 300000; // 5分間（ミリ秒）

    private final int port;
    private final ObjectMapper objectMapper;
    private final ExecutorService threadPool;
    private final Map<String, AgentSession> activeSessions;
    private final ActionEngine actionEngine;
    private final WhitelistManager whitelistManager;

    // ModSecurityアラートキュー（外部から注入）
    private final ModSecurityQueue modSecurityQueue;

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /**
     * コンストラクタ
     *

     * @param modSecurityQueue ModSecurityアラートキュー
     */
    public AgentTcpServer(ModSecurityQueue modSecurityQueue) {
        this(DEFAULT_PORT,modSecurityQueue);
    }

    /**
     * コンストラクタ（ポート指定）
     *
     * @param port リスニングポート
     * @param modSecurityQueue ModSecurityアラートキュー
     */
    public AgentTcpServer(int port, ModSecurityQueue modSecurityQueue) {
        this.port = port;
        this.modSecurityQueue = modSecurityQueue;
        this.objectMapper = new ObjectMapper();
        this.threadPool = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        this.activeSessions = new ConcurrentHashMap<>();

        try {
            this.actionEngine = new ActionEngine();
            this.whitelistManager = new WhitelistManager();
        } catch (Exception e) {
            AppLogger.error("ActionEngine initialization failed: " + e.getMessage());
            throw new RuntimeException("Failed to initialize ActionEngine", e);
        }
        AppLogger.info("AgentTcpServer initialized on port " + port + " with DbService integration");
    }

    /**
     * TCPサーバーを開始
     */
    public void start() throws IOException {
        if (running) {
            AppLogger.warn("TCP Server is already running");
            return;
        }

        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        running = true;

        AppLogger.info("Agent TCP Server started on port " + port);

        // 接続受付スレッド
        Thread acceptThread = new Thread(this::acceptConnections, "AgentTcpAcceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();

        // セッション監視スレッド（ハートビート確認）
        scheduleSessionMonitoring();
    }

    /**
     * セッション監視スケジューリング
     */
    private void scheduleSessionMonitoring() {
        try (ScheduledExecutorService sessionMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AgentSessionMonitor");
            t.setDaemon(true);
            return t;
        })) {
            sessionMonitor.scheduleAtFixedRate(() -> {
                try {
                    cleanupExpiredSessions();
                } catch (Exception e) {
                    AppLogger.error("Error in session monitoring: " + e.getMessage());
                }
            }, 60, 60, TimeUnit.SECONDS); // 1分間隔でセッション監視
        }
    }

    /**
     * 期限切れセッションのクリーンアップ
     */
    private void cleanupExpiredSessions() {
        long currentTime = System.currentTimeMillis();
        List<String> expiredSessions = new ArrayList<>();

        for (Map.Entry<String, AgentSession> entry : activeSessions.entrySet()) {
            AgentSession session = entry.getValue();
            // getLastActivityMillis()メソッドを使用して時刻比較
            if (currentTime - session.getLastActivityMillis() > SOCKET_TIMEOUT) {
                expiredSessions.add(entry.getKey());
            }
        }

        for (String sessionId : expiredSessions) {
            AgentSession session = activeSessions.remove(sessionId);
            if (session != null) {
                try {
                    session.close();
                    AppLogger.info("Expired session removed: " + sessionId);
                } catch (Exception e) {
                    AppLogger.warn("Error closing expired session: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 接続受付処理
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(SOCKET_TIMEOUT);
                clientSocket.setKeepAlive(true);

                String clientAddress = clientSocket.getRemoteSocketAddress().toString();
                AppLogger.info("New agent connection from " + clientAddress);

                // 新しい接続をスレッドプールで処理
                threadPool.submit(() -> handleClientConnection(clientSocket));

            } catch (IOException e) {
                if (running) {
                    AppLogger.warn("Error accepting connection: " + e.getMessage());
                }
            }
        }
    }

    /**
     * クライアント接続を処理
     */
    private void handleClientConnection(Socket clientSocket) {
        String clientAddress = clientSocket.getRemoteSocketAddress().toString();
        AgentSession session = null;

        try (DataInputStream input = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream output = new DataOutputStream(clientSocket.getOutputStream())) {

            // サーバー側でもストリーム安定化待機を追加
            try {
                Thread.sleep(50); // 50ms待機でサーバー側ストリーム準備を安定化
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 最初のメッセージタイプを読み取り
            byte firstMessageType = input.readByte();
            
            // 接続テストの場合は認証をスキップして直接処理
            if (firstMessageType == MSG_TYPE_CONNECTION_TEST) {
                // データ長を読み取り
                int dataLength = input.readInt();
                if (dataLength < 0 || dataLength > MAX_MESSAGE_SIZE) {
                    throw new IOException("Invalid message size: " + dataLength);
                }
                
                // データを読み取り（接続テストの場合は通常0バイト）
                byte[] data = new byte[dataLength];
                if (dataLength > 0) {
                    input.readFully(data);
                }
                
                // 接続テスト処理（セッションなしで実行）
                handleConnectionTestDirect(output);
                return;
            }
            
            // 接続テスト以外の場合は認証処理に進む
            // 最初のメッセージタイプを認証処理に渡す
            String agentName = authenticateAgentWithFirstByte(input, output, firstMessageType);
            if (agentName == null) {
                AppLogger.warn("Authentication failed for " + clientAddress);
                return;
            }

            // セッション作成
            session = new AgentSession(agentName, clientSocket, input, output);
            activeSessions.put(agentName, session);

            AppLogger.info("Agent authenticated: " + agentName + " from " + clientAddress);

            // メッセージ処理ループ
            while (running && !clientSocket.isClosed()) {
                try {
                    // メッセージタイプを読み取り
                    byte messageType = input.readByte();

                    // データ長を読み取り
                    int dataLength = input.readInt();
                    if (dataLength < 0 || dataLength > MAX_MESSAGE_SIZE) {
                        throw new IOException("Invalid message size: " + dataLength);
                    }

                    // データを読み取り
                    byte[] data = new byte[dataLength];
                    input.readFully(data);

                    // メッセージを処理
                    handleMessage(session, messageType, data);

                } catch (EOFException e) {
                    AppLogger.info("Agent disconnected: " + agentName);
                    break;
                } catch (SocketTimeoutException e) {
                    AppLogger.debug("Socket timeout for agent: " + agentName);
                } catch (IOException e) {
                    AppLogger.warn("Communication error with agent " + agentName + ": " + e.getMessage());
                    break;
                }
            }

        } catch (Exception e) {
            AppLogger.error("Error handling client connection: " + e.getMessage());
        } finally {
            // セッションをクリーンアップ
            if (session != null) {
                activeSessions.remove(session.getAgentName());
                session.close();
            }

            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.close();
                }
            } catch (IOException e) {
                AppLogger.debug("Error closing client socket: " + e.getMessage());
            }

            AppLogger.debug("Client connection closed: " + clientAddress);
        }
    }


    /**
     * 最初のメッセージタイプに基づいてエージェント認証処理
     */
    private String authenticateAgentWithFirstByte(DataInputStream input, DataOutputStream output, byte firstMessageType) throws IOException {
        try {
            AppLogger.debug("Starting authentication process (first byte)");

            // 接続テストの場合は認証をスキップして即座に成功レスポンス
            if (firstMessageType == MSG_TYPE_CONNECTION_TEST) {
                AppLogger.debug("Connection test detected - processing test request");

                // 接続テストの場合もデータ長を読み取る必要がある
                int dataLength = input.readInt();
                if (dataLength < 0 || dataLength > MAX_MESSAGE_SIZE) {
                    throw new IOException("Invalid message size: " + dataLength);
                }
                
                // データを読み取り（接続テストの場合は通常0バイト）
                byte[] data = new byte[dataLength];
                if (dataLength > 0) {
                    input.readFully(data);
                }
                
                output.writeByte(RESPONSE_SUCCESS);
                output.writeInt(0); // メッセージ長は0
                output.flush();
                AppLogger.debug("Connection test successful");
                return "connection-test"; // 特別なエージェント名
            }

            // 通常の認証処理
            if (firstMessageType != MSG_TYPE_AUTH) {
                AppLogger.warn("Invalid message type for authentication: " + firstMessageType);
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
                return null;
            }

            // データ長を読み取り
            int dataLength = input.readInt();
            if (dataLength <= 0 || dataLength > 1024) {
                AppLogger.warn("Invalid data length: " + dataLength);
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
                return null;
            }

            // APIキー長を読み取り
            int apiKeyLength = input.readInt();
            if (apiKeyLength <= 0 || apiKeyLength > 256) {
                AppLogger.warn("Invalid API key length: " + apiKeyLength);
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
                return null;
            }

            // APIキーを読み取り
            byte[] apiKeyBytes = new byte[apiKeyLength];
            input.readFully(apiKeyBytes);
            String apiKey = new String(apiKeyBytes, StandardCharsets.UTF_8);

            // エージェント名長を読み取り
            int agentNameLength = input.readInt();
            if (agentNameLength <= 0 || agentNameLength > 256) {
                AppLogger.warn("Invalid agent name length: " + agentNameLength);
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
                return null;
            }

            // エージェント名を読み取り
            byte[] agentNameBytes = new byte[agentNameLength];
            input.readFully(agentNameBytes);
            String agentName = new String(agentNameBytes, StandardCharsets.UTF_8);

            // APIキーを検証
            if (!VALID_API_KEY.equals(apiKey)) {
                AppLogger.warn("Authentication failed for agent: " + agentName + " (invalid API key)");
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
                return null;
            }

            // 認証成功
            AppLogger.info("Authentication successful for agent: " + agentName);
            output.writeByte(RESPONSE_SUCCESS);
            output.flush();

            return agentName;

        } catch (EOFException e) {
            AppLogger.warn("Authentication failed: Unexpected end of stream - " + e.getMessage());
            try {
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
            } catch (IOException ignored) {}
            return null;
        } catch (SocketTimeoutException e) {
            AppLogger.warn("Authentication failed: Socket timeout - " + e.getMessage());
            try {
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
            } catch (IOException ignored) {}
            return null;
        } catch (IOException e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown IO error during authentication";
            AppLogger.error("Authentication IO error: " + errorMsg + " (Type: " + e.getClass().getSimpleName() + ")");
            try {
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
            } catch (IOException ignored) {}
            throw e;
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error during authentication";
            AppLogger.error("Authentication unexpected error: " + errorMsg + " (Type: " + e.getClass().getSimpleName() + ")");
            try {
                output.writeByte(RESPONSE_AUTH_FAILED);
                output.flush();
            } catch (IOException ignored) {}
            throw new IOException("Authentication failed: " + errorMsg, e);
        }
    }

    /**
     * メッセージ処理
     */
    private void handleMessage(AgentSession session, byte messageType, byte[] data) throws IOException {
        try {
            switch (messageType) {
                case MSG_TYPE_CONNECTION_TEST:
                    handleConnectionTest(session, data);
                    break;
                case MSG_TYPE_REGISTER:
                    handleServerRegistration(session, data);
                    break;
                case MSG_TYPE_LOG_BATCH:
                    handleLogBatch(session, data);
                    break;
                case MSG_TYPE_HEARTBEAT:
                    handleHeartbeat(session, data);
                    break;
                case MSG_TYPE_BLOCK_REQUEST:
                    handleBlockRequest(session, data);
                    break;
                case MSG_TYPE_UNREGISTER:
                    handleServerUnregistration(session, data);
                    break;
                default:
                    AppLogger.warn("Unknown message type: " + messageType + " from " + session.getAgentName());
                    session.sendResponse(RESPONSE_ERROR, "Unknown message type");
            }

            // 最終アクティビティ時刻を更新
            session.updateLastActivity();

        } catch (Exception e) {
            AppLogger.error("Error handling message type " + messageType + " from " + session.getAgentName() + ": " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Message processing failed");
        }
    }

    /**
     * 接続テスト処理（認証不要の軽量テスト）
     */
    private void handleConnectionTest(AgentSession session, byte[] data) throws IOException {
        try {
            // 接続テストは単純にSUCCESSレスポンスを返すだけ
            AppLogger.debug("Connection test received from: " + session.getAgentName());
            session.sendResponse(RESPONSE_SUCCESS, "Connection test successful");

        } catch (Exception e) {
            AppLogger.debug("Connection test error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Connection test failed");
        }
    }

    /**
     * 接続テスト処理（認証なし・直接処理）
     */
    private void handleConnectionTestDirect(DataOutputStream output) throws IOException {
        try {
            // 接続テストは単純にSUCCESSレスポンスを返すだけ
            output.writeByte(RESPONSE_SUCCESS);
            output.writeInt(0); // メッセージ長は0
            output.flush();
            AppLogger.debug("Connection test successful (direct)");

        } catch (Exception e) {
            AppLogger.debug("Connection test error (direct): " + e.getMessage());
            try {
                output.writeByte(RESPONSE_ERROR);
                byte[] errorMsg = "Connection test failed".getBytes(StandardCharsets.UTF_8);
                output.writeInt(errorMsg.length);
                output.write(errorMsg);
                output.flush();
            } catch (IOException ignored) {}
        }
    }

    /**
     * agentサーバー登録処理
     */
    private void handleServerRegistration(AgentSession session, byte[] data) throws IOException {
        try {
            // データストリームから文字列を順次読み取り
            ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
            DataInputStream dataInput = new DataInputStream(byteStream);

            // 1. APIキーを読み取り（認証は済んでいるが、プロトコル整合性のため）
            String apiKey = readStringFromStream(dataInput);

            // APIキー検証（セキュリティ強化）
            if (!VALID_API_KEY.equals(apiKey)) {
                AppLogger.warn("Invalid API key in server registration from " + session.getAgentName());
                session.sendResponse(RESPONSE_ERROR, "Invalid API key");
                return;
            }

            // 2. サーバー情報JSONを読み取り
            String serverInfoJson = readStringFromStream(dataInput);
            Map<String, Object> serverInfo = objectMapper.readValue(serverInfoJson, new TypeReference<>() {});

            // DbServiceを使用してエージェント登録
            String registrationId = registerOrUpdateAgent(serverInfo);

            if (registrationId != null) {
                session.setRegistrationId(registrationId);
                session.sendResponse(RESPONSE_SUCCESS, registrationId);
            } else {
                session.sendResponse(RESPONSE_ERROR, "Registration failed");
            }

        } catch (Exception e) {
            AppLogger.error("Server registration error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Registration processing failed");
        }
    }

    /**
     * データストリームから文字列を読み取り
     */
    private String readStringFromStream(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > 65536) { // 64KB制限
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * agentサーバー登録解除処理
     */
    private void handleServerUnregistration(AgentSession session, byte[] data) throws IOException {
        try {
            String registrationId = session.getRegistrationId();
            if (registrationId == null) {
                session.sendResponse(RESPONSE_ERROR, "Not registered");
                return;
            }

            // DbServiceを使用してエージェントをinactive化
            int affected = deactivateAgent(registrationId);

            if (affected > 0) {
                session.sendResponse(RESPONSE_SUCCESS, "Unregistered successfully");
            } else {
                session.sendResponse(RESPONSE_ERROR, "Unregistration failed");
            }

        } catch (Exception e) {
            AppLogger.error("Server unregistration error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Unregistration processing failed");
        }
    }

    /**
     * ハートビート処理
     */
    private void handleHeartbeat(AgentSession session, byte[] data) throws IOException {
        try {
            String registrationId = session.getRegistrationId();
            if (registrationId == null) {
                session.sendResponse(RESPONSE_ERROR, "Not registered");
                return;
            }

            // エージェントからのハートビートデータ（JSON）を解析
            try {
                String jsonData = new String(data, StandardCharsets.UTF_8);
                Map<String, Object> heartbeatData = objectMapper.readValue(jsonData, new TypeReference<>() {});

                // ハートビートデータをログ出力（デバッグ用）
                String agentName = (String) heartbeatData.get("agentName");
                AppLogger.debug("Heartbeat received from: " + agentName + " (JSON format)");

            } catch (Exception e) {
                // JSON解析に失敗した場合もハートビートとして処理を続行
                AppLogger.debug("Heartbeat data parsing failed, but processing continues: " + e.getMessage());
            }

            // DbServiceを使用してハートビート更新
            int updated = updateAgentHeartbeat(registrationId);

            if (updated > 0) {
                session.sendResponse(RESPONSE_SUCCESS, "Heartbeat acknowledged");
            } else {
                session.sendResponse(RESPONSE_ERROR, "Registration not found");
            }

        } catch (Exception e) {
            AppLogger.error("Heartbeat processing error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Heartbeat processing failed");
        }
    }

    /**
     * ログバッチ処理
     */
    private void handleLogBatch(AgentSession session, byte[] data) throws IOException {
        try {
            String registrationId = session.getRegistrationId();
            if (registrationId == null) {
                session.sendResponse(RESPONSE_ERROR, "Not registered");
                return;
            }

            // ログバッチデータ（JSON）を解析
            String jsonData = new String(data, StandardCharsets.UTF_8);
            Map<String, Object> logBatch = objectMapper.readValue(jsonData, new TypeReference<>() {});
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> logs = (List<Map<String, Object>>) logBatch.get("logs");

            if (logs != null && !logs.isEmpty()) {
                // processLogEntriesメソッドを呼び出してログ処理を実行
                int processedCount = processLogEntries(session, logs);

                // ログ処理統計を更新
                if (processedCount > 0) {
                    updateAgentLogStats(registrationId, processedCount);
                }
                
                session.sendResponse(RESPONSE_SUCCESS, "Processed " + processedCount + " logs");
                AppLogger.debug("Processed " + processedCount + " logs from agent: " + registrationId);
            } else {
                session.sendResponse(RESPONSE_SUCCESS, "No logs to process");
            }

        } catch (Exception e) {
            AppLogger.error("Log batch processing error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Log processing failed");
        }
    }

    /**
     * ブロック要求処理
     */
    private void handleBlockRequest(AgentSession session, byte[] data) throws IOException {
        try {
            String registrationId = session.getRegistrationId();
            if (registrationId == null) {
                AppLogger.warn("Block request failed: Agent not registered - " + session.getAgentName());
                session.sendResponse(RESPONSE_ERROR, "Not registered");
                return;
            }

            // エージェントからのデータを解析（デバッグ用）
            try {
                String jsonData = new String(data, StandardCharsets.UTF_8);
                AppLogger.debug("Block request data from " + session.getAgentName() + ": " + jsonData);
            } catch (Exception e) {
                AppLogger.debug("Block request data parsing failed (processing continues): " + e.getMessage());
            }

            // DbServiceを使用してブロック要求を取得
            List<Map<String, Object>> blockRequests = selectPendingBlockRequests(registrationId, 10);

            // レスポンス構築
            Map<String, Object> response = new HashMap<>();
            response.put("requests", blockRequests);
            String responseJson = objectMapper.writeValueAsString(response);
            session.sendResponse(RESPONSE_SUCCESS, responseJson);

            // 明確なログ出力
            if (blockRequests.isEmpty()) {
                AppLogger.debug("No block requests found for " + session.getAgentName() + " (registration: " + registrationId + ")");
            } else {
                AppLogger.debug("Sent " + blockRequests.size() + " block requests to " + session.getAgentName());
            }

        } catch (Exception e) {
            AppLogger.error("Block request processing error: " + e.getMessage());
            session.sendResponse(RESPONSE_ERROR, "Block request processing failed: " + e.getMessage());
        }
    }

    /**
     * ログエントリを処理（v3.0.0 - ModSecurityキューベース関連付けシステム）
     */
    private int processLogEntries(AgentSession session, List<Map<String, Object>> logs) {
        int processedCount = 0;
        String registrationId = session.getRegistrationId();

        AppLogger.info("Processing " + logs.size() + " log entries from agent: " + session.getAgentName());

        // 時系列順にソートして関連付けの精度を向上
        logs.sort((a, b) -> {
            String timeA = (String) a.getOrDefault("collectedAt", "");
            String timeB = (String) b.getOrDefault("collectedAt", "");
            return timeA.compareTo(timeB);
        });

        // 重複チェック用のSet（同一リクエストの重複処理を防ぐ）
        Set<String> processedRequests = new HashSet<>();
        
        // 処理したサーバー名を記録（重複登録防止）
        Set<String> processedServers = new HashSet<>();

        for (Map<String, Object> logData : logs) {
            try {
                String rawLogLine = (String) logData.get("rawLogLine");
                String serverName = (String) logData.get("serverName");
                String sourcePath = (String) logData.get("sourcePath");
                String collectedAt = (String) logData.get("collectedAt");

                // エージェントから受け取ったサーバー名をそのまま使用
                String actualServerName = serverName;

                // サーバー自動登録処理（新規サーバーの場合）
                if (actualServerName != null && !processedServers.contains(actualServerName)) {
                    try {
                        registerOrUpdateServer(actualServerName, "エージェント自動登録", sourcePath);

                        // サーバー名に対してadmin/operator/viewerロールを追加
                        // ロール追加は registerOrUpdateServer 側で新規登録時のみ実行されるため、ここでは呼び出さない

                        processedServers.add(actualServerName);
                        AppLogger.debug("サーバー自動登録/更新: " + actualServerName);
                    } catch (Exception e) {
                        AppLogger.warn("サーバー自動登録エラー: " + actualServerName + " - " + e.getMessage());
                    }
                }

                // ModSecurityエラーログの処理（error.logからの情報）
                if (sourcePath != null && sourcePath.contains("error.log")) {
                    // エラーログからModSecurity情報を抽出してキューに追加
                    if (logData.containsKey("request")) {
                        String request = (String) logData.get("request");
                        if (ModSecHandler.isModSecurityRawLog(request)) {
                            ModSecHandler.processModSecurityAlertToQueue(request, actualServerName, modSecurityQueue);
                            AppLogger.debug("ModSecurityアラート処理実行: サーバー=" + actualServerName);
                            continue; // ModSecurityエラーログは詳細情報保存のみで、access_logには記録しない
                        }
                    }
                    // error.logの通常ログもスキップ（access.logのみ処理）
                    continue;
                }

                // access.logからのHTTPリクエストのみ処理
                if (sourcePath == null || !sourcePath.contains("access.log")) {
                    continue;
                }

                Map<String, Object> parsedLog;

                // エージェントからの解析済みデータを直接処理する場合
                if (rawLogLine == null || rawLogLine.trim().isEmpty()) {
                    AppLogger.debug("エージェントからの解析済みデータを処理中: " + logData.get("summary"));

                    // エージェントから送信されたフィールドを使用してparsedLogを構築
                    parsedLog = buildParsedLogFromAgentData(logData);

                    if (parsedLog == null) {
                        AppLogger.debug("空のログ行をスキップ: " + logData);
                        continue;
                    }
                } else {
                    // 通常のHTTPリクエスト行の処理（rawLogLineが存在する場合）
                    parsedLog = LogParser.parseLogLine(rawLogLine);
                    if (parsedLog == null) {
                        AppLogger.warn("ログ解析失敗: [" + actualServerName + "] " + rawLogLine);
                        continue; // パース失敗時はスキップ
                    }
                }

                // 重複チェック用のキーを生成（時刻を含めて厳密にチェック）
                String requestKey = generateStrictRequestKey(parsedLog, actualServerName);
                if (processedRequests.contains(requestKey)) {
                    AppLogger.debug("重複リクエストをスキップ: " + requestKey);
                    continue;
                }
                processedRequests.add(requestKey);

                // favicon.ico等の巻き込み検知を除外
                String fullUrl = (String) parsedLog.get("full_url");
                if (isIgnorableRequest(fullUrl)) {
                    AppLogger.debug("無視対象リクエストをスキップ: " + fullUrl);
                    continue;
                }

                AppLogger.debug("HTTPリクエスト処理: " + parsedLog.get("method") + " " + parsedLog.get("full_url") + " " + parsedLog.get("status_code"));

                // サーバー情報とエージェント情報を追加
                parsedLog.put("server_name", actualServerName);
                parsedLog.put("source_path", sourcePath);
                parsedLog.put("collected_at", collectedAt != null ? collectedAt : LocalDateTime.now().toString());
                parsedLog.put("agent_registration_id", registrationId);

                // 初期状態ではModSecurityブロックはfalse
                parsedLog.put("blocked_by_modsec", false);

                // DbServiceを使用してaccess_logテーブルに保存
                Long accessLogId = insertAccessLog(parsedLog);
                if (accessLogId != null) {
                    processedCount++;
                    AppLogger.debug("access_log保存成功: ID=" + accessLogId + " (" + parsedLog.get("method") + " " + parsedLog.get("full_url") + ")");

                    // サーバーのlast_log_received時刻を更新
                    try {
                        updateServerLastLogReceived(actualServerName);
                        AppLogger.debug("サーバー最終ログ受信時刻更新: " + actualServerName);
                    } catch (Exception e) {
                        AppLogger.warn("サーバー最終ログ受信時刻更新エラー: " + actualServerName + " - " + e.getMessage());
                    }

                    // 既存URLの再アクセス時にホワイトリスト状態を再評価
                    String method = (String) parsedLog.get("method");
                    String clientIp = (String) parsedLog.get("ip_address");

                    if (actualServerName != null && method != null && fullUrl != null && clientIp != null) {
                        whitelistManager.updateExistingUrlWhitelistStatusOnAccess(
                            actualServerName, method, fullUrl, clientIp
                        );
                    }

                    // ModSecurityアラートキューから一致するアラートを検索
                    LocalDateTime accessTime = (LocalDateTime) parsedLog.get("access_time");

                    List<ModSecurityQueue.ModSecurityAlert> matchingAlerts =
                        modSecurityQueue.findMatchingAlerts(actualServerName, fullUrl, accessTime);

                    if (!matchingAlerts.isEmpty()) {
                        AppLogger.info("ModSecurityアラート一致検出: " + matchingAlerts.size() + "件, access_log ID=" + accessLogId);

                        // access_logのblocked_by_modsecをtrueに更新
                        updateAccessLogModSecStatus(accessLogId, true);

                        // 一致したアラートをmodsec_alertsテーブルに保存
                        for (ModSecurityQueue.ModSecurityAlert alert : matchingAlerts) {
                            ModSecHandler.saveModSecurityAlertToDatabase(accessLogId, alert);
                            AppLogger.debug("ModSecurityアラート保存: access_log ID=" + accessLogId +
                                          ", ルール=" + alert.ruleId() + ", メッセージ=" + alert.message());
                        }
                    } else {
                        AppLogger.debug("ModSecurityアラート一致なし: " + fullUrl);
                    }

                    // 攻撃パターン識別とURL登録
                    processUrlAndAttackPattern(parsedLog);

                    // アクション実行エンジンでの脅威対応（ModSecurityブロック状態を確認）
                    boolean blockedByModSec = !matchingAlerts.isEmpty();
                    executeSecurityActions(parsedLog, blockedByModSec);
                } else {
                    AppLogger.error("access_log保存失敗: " + parsedLog);
                }

            } catch (Exception e) {
                AppLogger.warn("Error processing log entry from " + session.getAgentName() + ": " + e.getMessage());
                AppLogger.debug("Failed log data: " + logData);
            }
        }

        AppLogger.info("Successfully processed " + processedCount + " log entries from " + session.getAgentName());
        return processedCount;
    }

    /**
     * より厳密な重複チェック用のリクエストキーを生成
     */
    private String generateStrictRequestKey(Map<String, Object> parsedLog, String serverName) {
        String method = (String) parsedLog.get("method");
        String fullUrl = (String) parsedLog.get("full_url");
        String ipAddress = (String) parsedLog.get("ip_address");
        Object accessTime = parsedLog.get("access_time");
        Object statusCode = parsedLog.get("status_code");

        return String.format("%s|%s|%s|%s|%s|%s",
            serverName != null ? serverName : "",
            method != null ? method : "",
            fullUrl != null ? fullUrl : "",
            ipAddress != null ? ipAddress : "",
            statusCode != null ? statusCode.toString() : "",
            accessTime != null ? accessTime.toString() : ""
        );
    }

    /**
     * エージェントデータからparsedLogオブジェクトを構築（URLデコード修正版）
     */
    private Map<String, Object> buildParsedLogFromAgentData(Map<String, Object> logData) {
        try {
            Map<String, Object> parsedLog = new HashMap<>();

            // HTTPメソッドを取得
            String httpMethod = (String) logData.get("httpMethod");
            if (httpMethod == null || httpMethod.trim().isEmpty()) {
                return null;
            }
            parsedLog.put("method", httpMethod);

            // URLを取得してデコード（修正版）
            String requestUrl = (String) logData.get("requestUrl");
            if (requestUrl == null || requestUrl.trim().isEmpty()) {
                return null;
            }

            // URLデコードを実行（複数回デコードが必要な場合もある）
            String decodedUrl = UrlCodec.decode(requestUrl);
            parsedLog.put("full_url", decodedUrl);

            // ステータスコードを取得
            Object statusCodeObj = logData.get("statusCode");
            Integer statusCode = null;
            if (statusCodeObj instanceof Number) {
                statusCode = ((Number) statusCodeObj).intValue();
            } else if (statusCodeObj instanceof String) {
                try {
                    statusCode = Integer.parseInt((String) statusCodeObj);
                } catch (NumberFormatException e) {
                    AppLogger.warn("Invalid status code: " + statusCodeObj);
                    return null;
                }
            }
            if (statusCode == null) {
                return null;
            }
            parsedLog.put("status_code", statusCode);

            // IPアドレスを取得
            String clientIp = (String) logData.get("clientIp");
            if (clientIp == null || clientIp.trim().isEmpty()) {
                return null;
            }
            parsedLog.put("ip_address", clientIp);

            // アクセス時刻を取得
            String timestamp = (String) logData.get("timestamp");
            if (timestamp != null && !timestamp.trim().isEmpty()) {
                try {
                    // NGINXログのタイムスタンプ形式をパース（例：08/Aug/2025:16:13:25 +0900）
                    LocalDateTime accessTime = parseNginxTimestamp(timestamp);
                    parsedLog.put("access_time", accessTime);
                } catch (Exception e) {
                    AppLogger.debug("Failed to parse timestamp: " + timestamp);
                    parsedLog.put("access_time", LocalDateTime.now());
                }
            } else {
                parsedLog.put("access_time", LocalDateTime.now());
            }

            // レスポンスサイズ等の追加情報
            Object responseSize = logData.get("responseSize");
            if (responseSize instanceof Number) {
                parsedLog.put("response_size", ((Number) responseSize).longValue());
            } else if (responseSize instanceof String) {
                try {
                    parsedLog.put("response_size", Long.parseLong((String) responseSize));
                } catch (NumberFormatException e) {
                    parsedLog.put("response_size", 0L);
                }
            } else {
                parsedLog.put("response_size", 0L);
            }

            // User-Agent情報
            String userAgent = (String) logData.get("userAgent");
            if (userAgent != null) {
                parsedLog.put("user_agent", userAgent);
            }

            // Referer情報
            String referer = (String) logData.get("referer");
            if (referer != null && !"-".equals(referer)) {
                parsedLog.put("referer", referer);
            }

            return parsedLog;

        } catch (Exception e) {
            AppLogger.error("Error building parsed log from agent data: " + e.getMessage());
            return null;
        }
    }

    /**
     * NGINXタイムスタンプをパース（例：08/Aug/2025:16:13:25 +0900）
     */
    private LocalDateTime parseNginxTimestamp(String timestamp) {
        try {
            // NGINXのログフォーマット：dd/MMM/yyyy:HH:mm:ss Z
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
            java.time.ZonedDateTime zonedDateTime = java.time.ZonedDateTime.parse(timestamp, formatter);
            return zonedDateTime.toLocalDateTime();
        } catch (Exception e) {
            AppLogger.debug("Failed to parse NGINX timestamp: " + timestamp + " - " + e.getMessage());
            return LocalDateTime.now();
        }
    }


    /**
     * TCPサーバーを停止
     */
    public void stop() {
        running = false;

        // 既存の停止処理
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                AppLogger.debug("Error closing server socket: " + e.getMessage());
            }
        }

        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // アクティブセッションをクローズ
        for (AgentSession session : activeSessions.values()) {
            session.close();
        }
        activeSessions.clear();

        AppLogger.info("Agent TCP Server stopped");
    }


    /**
     * URL登録と攻撃パターン識別処理
     */
    private void processUrlAndAttackPattern(Map<String, Object> parsedLog) {
        try {
            String serverName = (String) parsedLog.get("server_name");
            String method = (String) parsedLog.get("method");
            String fullUrl = (String) parsedLog.get("full_url");
            String clientIp = (String) parsedLog.get("ip_address"); // IPアドレス情報を取得

            if (serverName == null || method == null || fullUrl == null) {
                return;
            }

            // 既存URLの重複チェックを実行
            boolean urlExists = existsUrlRegistryEntry(serverName, method, fullUrl);

            if (urlExists) {
                // 既存URLの場合は登録をスキップ
                AppLogger.debug("既存URL検出、登録スキップ: " + serverName + " - " + method + " " + fullUrl);

                // 既存URLの再アクセス時にホワイトリスト状態を再評価
                if (clientIp != null) {
                    whitelistManager.updateExistingUrlWhitelistStatusOnAccess(
                        serverName, method, fullUrl, clientIp
                    );
                }
                return;
            }

            // 攻撃パターン識別を実行
            String attackType = AttackPattern.detectAttackTypeYaml(fullUrl,
                "/app/config/attack_patterns.yaml", "/app/config/attack_patterns_override.yaml");

            // ホワイトリスト判定を実行（IPアドレス情報を使用）
            boolean isWhitelisted = whitelistManager.determineWhitelistStatus(clientIp);

            // DbServiceを使用して新規URL登録
            boolean registered = registerUrlRegistryEntry(serverName, method, fullUrl, isWhitelisted, attackType);
            if (registered) {
                if (isWhitelisted) {
                    AppLogger.info("ホワイトリストURL登録: " + serverName + " - " + method + " " + fullUrl + " from " + clientIp);
                } else {
                    AppLogger.info("新規URL登録: " + serverName + " - " + method + " " + fullUrl + " (攻撃タイプ: " + attackType + ")");
                }
            } else {
                AppLogger.error("URL登録失敗: " + serverName + " - " + method + " " + fullUrl);
            }

        } catch (Exception e) {
            AppLogger.error("Error registering URL to registry: " + e.getMessage());
        }
    }

    /**
     * セキュリティアクションの実行
     */
    private void executeSecurityActions(Map<String, Object> parsedLog, boolean blockedByModSec) {
        try {
            if (blockedByModSec) {
                String clientIp = (String) parsedLog.get("ip_address");
                String attackType = (String) parsedLog.get("attack_type");

                if (clientIp != null && attackType != null && !"CLEAN".equals(attackType)) {
                    // セキュリティアクションを実行
                    actionEngine.executeAction("attack_detected", parsedLog);
                    AppLogger.info("Security action executed for attack: " + attackType + " from " + clientIp);
                }
            }
        } catch (Exception e) {
            AppLogger.error("Error executing security actions: " + e.getMessage());
        }
    }

    /**
     * 無視すべきリクエストかどうかを判定
     */
    private boolean isIgnorableRequest(String url) {
        if (url == null || url.isEmpty()) {
            return true;
        }

        // 静的ファイルやよくある無害なリクエストを無視
        return url.endsWith(".ico") ||
               url.endsWith(".css") ||
               url.endsWith(".js") ||
               url.endsWith(".png") ||
               url.endsWith(".jpg") ||
               url.endsWith(".gif") ||
               url.equals("/favicon.ico") ||
               url.equals("/robots.txt");
    }
}
