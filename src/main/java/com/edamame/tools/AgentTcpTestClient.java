package com.edamame.tools;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * エージェントTCP通信テストクライアント
 *
 * AgentTcpServerとの通信をテストするためのクライアント
 * 各種プロトコルメッセージの送受信をテスト
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class AgentTcpTestClient {

    private static final Logger logger = Logger.getLogger(AgentTcpTestClient.class.getName());

    // プロトコル定数
    private static final byte MSG_TYPE_LOG_BATCH = 0x01;
    private static final byte MSG_TYPE_HEARTBEAT = 0x02;
    private static final byte MSG_TYPE_BLOCK_REQUEST = 0x03;
    private static final byte MSG_TYPE_AUTH = 0x04;
    private static final byte MSG_TYPE_REGISTER = 0x10;
    private static final byte MSG_TYPE_UNREGISTER = 0x11;

    private static final byte RESPONSE_SUCCESS = 0x00;
    private static final byte RESPONSE_AUTH_FAILED = 0x02;

    // テスト設定
    private static final String TEST_API_KEY = "edamame-agent-api-key-2025";
    private static final String TEST_AGENT_NAME = "test-agent-001";
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 2591;

    private final ObjectMapper objectMapper;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public AgentTcpTestClient() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * サーバーに接続
     */
    public boolean connect() {
        try {
            log("サーバーに接続中... " + SERVER_HOST + ":" + SERVER_PORT);
            socket = new Socket(SERVER_HOST, SERVER_PORT);
            socket.setSoTimeout(10000); // 10秒タイムアウト

            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());

            log("接続成功: " + socket.getRemoteSocketAddress());
            return true;

        } catch (IOException e) {
            log("接続失敗: " + e.getMessage());
            return false;
        }
    }

    /**
     * サーバーから切断
     */
    public void disconnect() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
                log("サーバーから切断しました");
            }
        } catch (IOException e) {
            log("切断エラー: " + e.getMessage());
        }
    }

    /**
     * 認証テスト
     */
    public boolean testAuthentication() {
        try {
            log("認証テスト開始...");

            // 認証メッセージを送信
            sendAuthMessage();

            // レスポンスを受信
            byte responseCode = input.readByte();

            if (responseCode == RESPONSE_SUCCESS) {
                log("認証成功");
                return true;
            } else if (responseCode == RESPONSE_AUTH_FAILED) {
                log("認証失敗");
                return false;
            } else {
                log("不明なレスポンス: " + responseCode);
                return false;
            }

        } catch (IOException e) {
            log("認証テストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * サーバー登録テスト
     */
    public boolean testServerRegistration() {
        try {
            log("サーバー登録テスト開始...");

            // サーバー情報を作成
            Map<String, Object> serverInfo = createTestServerInfo();
            String jsonData = objectMapper.writeValueAsString(serverInfo);

            // 登録メッセージを送信
            sendMessage(MSG_TYPE_REGISTER, jsonData.getBytes(StandardCharsets.UTF_8));

            // レスポンスを受信
            byte responseCode = input.readByte();
            int responseLength = input.readInt();
            byte[] responseData = new byte[responseLength];
            input.readFully(responseData);
            String responseMessage = new String(responseData, StandardCharsets.UTF_8);

            if (responseCode == RESPONSE_SUCCESS) {
                log("サーバー登録成功: " + responseMessage);
                return true;
            } else {
                log("サーバー登録失敗: " + responseMessage);
                return false;
            }

        } catch (Exception e) {
            log("サーバー登録テストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * ハートビートテスト
     */
    public boolean testHeartbeat() {
        try {
            log("ハートビートテスト開始...");

            // ハートビートメッセージを送信
            sendMessage(MSG_TYPE_HEARTBEAT, new byte[0]);

            // レスポンスを受信
            byte responseCode = input.readByte();
            int responseLength = input.readInt();
            byte[] responseData = new byte[responseLength];
            input.readFully(responseData);
            String responseMessage = new String(responseData, StandardCharsets.UTF_8);

            if (responseCode == RESPONSE_SUCCESS) {
                log("ハートビート成功: " + responseMessage);
                return true;
            } else {
                log("ハートビート失敗: " + responseMessage);
                return false;
            }

        } catch (IOException e) {
            log("ハートビートテストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * ログバッチ送信テスト
     */
    public boolean testLogBatch() {
        try {
            log("ログバッチ送信テスト開始...");

            // テストログバッチを作成
            Map<String, Object> logBatch = createTestLogBatch();
            String jsonData = objectMapper.writeValueAsString(logBatch);

            // ログバッチメッセージを送信
            sendMessage(MSG_TYPE_LOG_BATCH, jsonData.getBytes(StandardCharsets.UTF_8));

            // レスポンスを受信
            byte responseCode = input.readByte();
            int responseLength = input.readInt();
            byte[] responseData = new byte[responseLength];
            input.readFully(responseData);
            String responseMessage = new String(responseData, StandardCharsets.UTF_8);

            if (responseCode == RESPONSE_SUCCESS) {
                log("ログバッチ送信成功: " + responseMessage);
                return true;
            } else {
                log("ログバッチ送信失敗: " + responseMessage);
                return false;
            }

        } catch (Exception e) {
            log("ログバッチ送信テストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * ブロック要求取得テスト
     */
    public boolean testBlockRequest() {
        try {
            log("ブロック要求取得テスト開始...");

            // ブロック要求メッセージを送信
            sendMessage(MSG_TYPE_BLOCK_REQUEST, new byte[0]);

            // レスポンスを受信
            byte responseCode = input.readByte();
            int responseLength = input.readInt();
            byte[] responseData = new byte[responseLength];
            input.readFully(responseData);
            String responseMessage = new String(responseData, StandardCharsets.UTF_8);

            if (responseCode == RESPONSE_SUCCESS) {
                log("ブロック要求取得成功: " + responseMessage);
                return true;
            } else {
                log("ブロック要求取得失敗: " + responseMessage);
                return false;
            }

        } catch (IOException e) {
            log("ブロック要求取得テストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * サーバー登録解除テスト
     */
    public boolean testServerUnregistration() {
        try {
            log("サーバー登録解除テスト開始...");

            // 登録解除メッセージを送信
            sendMessage(MSG_TYPE_UNREGISTER, new byte[0]);

            // レスポンスを受信
            byte responseCode = input.readByte();
            int responseLength = input.readInt();
            byte[] responseData = new byte[responseLength];
            input.readFully(responseData);
            String responseMessage = new String(responseData, StandardCharsets.UTF_8);

            if (responseCode == RESPONSE_SUCCESS) {
                log("サーバー登録解除成功: " + responseMessage);
                return true;
            } else {
                log("サーバー登録解除失敗: " + responseMessage);
                return false;
            }

        } catch (IOException e) {
            log("サーバー登録解除テストエラー: " + e.getMessage());
            return false;
        }
    }

    /**
     * 認証メッセージを送信
     */
    private void sendAuthMessage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // APIキー長とAPIキー
        byte[] apiKeyBytes = TEST_API_KEY.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(apiKeyBytes.length);
        dos.write(apiKeyBytes);

        // エージェント名長とエージェント名
        byte[] agentNameBytes = TEST_AGENT_NAME.getBytes(StandardCharsets.UTF_8);
        dos.writeInt(agentNameBytes.length);
        dos.write(agentNameBytes);

        byte[] data = baos.toByteArray();

        // メッセージを送信
        output.writeByte(MSG_TYPE_AUTH);
        output.writeInt(data.length);
        output.write(data);
        output.flush();
    }

    /**
     * 一般的なメッセージを送信
     */
    private void sendMessage(byte messageType, byte[] data) throws IOException {
        output.writeByte(messageType);
        output.writeInt(data.length);
        output.write(data);
        output.flush();
    }

    /**
     * テスト用サーバー情報を作成
     */
    private Map<String, Object> createTestServerInfo() {
        Map<String, Object> serverInfo = new HashMap<>();
        serverInfo.put("serverName", "test-nginx-server");
        serverInfo.put("serverIp", "192.168.1.100");
        serverInfo.put("serverPort", 80);
        serverInfo.put("serverType", "host");
        serverInfo.put("hostname", "test-server.example.com");
        serverInfo.put("osName", "Linux");
        serverInfo.put("osVersion", "Ubuntu 22.04");
        serverInfo.put("javaVersion", "21.0.1");
        serverInfo.put("nginxLogPaths", List.of("/var/log/nginx/access.log"));
        serverInfo.put("iptablesEnabled", true);
        serverInfo.put("agentVersion", "1.0.0");
        serverInfo.put("registeredAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));

        return serverInfo;
    }

    /**
     * テスト用ログバッチを作成
     */
    private Map<String, Object> createTestLogBatch() {
        List<Map<String, Object>> logs = new ArrayList<>();

        // テストログエントリ1
        Map<String, Object> log1 = new HashMap<>();
        log1.put("clientIp", "192.168.1.200");
        log1.put("timestamp", "08/Jan/2025:10:00:00 +0000");
        log1.put("request", "GET /index.html HTTP/1.1");
        log1.put("statusCode", 200);
        log1.put("responseSize", "1024");
        log1.put("referer", "https://example.com/");
        log1.put("userAgent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        log1.put("sourcePath", "/var/log/nginx/access.log");
        log1.put("serverName", "test-nginx-server");
        log1.put("collectedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        logs.add(log1);

        // テストログエントリ2
        Map<String, Object> log2 = new HashMap<>();
        log2.put("clientIp", "192.168.1.201");
        log2.put("timestamp", "08/Jan/2025:10:00:01 +0000");
        log2.put("request", "POST /api/users HTTP/1.1");
        log2.put("statusCode", 201);
        log2.put("responseSize", "512");
        log2.put("referer", "-");
        log2.put("userAgent", "curl/7.68.0");
        log2.put("sourcePath", "/var/log/nginx/access.log");
        log2.put("serverName", "test-nginx-server");
        log2.put("collectedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        logs.add(log2);

        Map<String, Object> logBatch = new HashMap<>();
        logBatch.put("logs", logs);

        return logBatch;
    }

    /**
     * ログ出力
     */
    private void log(String message) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        System.out.printf("[%s] %s%n", timestamp, message);
    }

    /**
     * 全テストを実行
     */
    public void runAllTests() {
        log("=== エージェントTCP通信テスト開始 ===");

        int totalTests = 0;
        int passedTests = 0;

        // 1. 接続テスト
        totalTests++;
        if (connect()) {
            passedTests++;
        }

        // 2. 認証テスト
        totalTests++;
        if (testAuthentication()) {
            passedTests++;
        }

        // 3. サーバー登録テスト
        totalTests++;
        if (testServerRegistration()) {
            passedTests++;
        }

        // 4. ハートビートテスト
        totalTests++;
        if (testHeartbeat()) {
            passedTests++;
        }

        // 5. ログバッチ送信テスト
        totalTests++;
        if (testLogBatch()) {
            passedTests++;
        }

        // 6. ブロック要求取得テスト
        totalTests++;
        if (testBlockRequest()) {
            passedTests++;
        }

        // 7. サーバー登録解除テスト
        totalTests++;
        if (testServerUnregistration()) {
            passedTests++;
        }

        // 8. 切断テスト
        disconnect();

        // 結果表示
        log("=== テスト結果 ===");
        log(String.format("実行: %d, 成功: %d, 失敗: %d", totalTests, passedTests, totalTests - passedTests));
        if (passedTests == totalTests) {
            log("すべてのテストが成功しました！");
        } else {
            log("一部のテストが失敗しました。");
        }
        log("==================");
    }

    /**
     * メ��ンメソッド
     */
    public static void main(String[] args) {
        AgentTcpTestClient client = new AgentTcpTestClient();

        try {
            client.runAllTests();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "テスト実行中にエラーが発生しました", e);
        }
    }
}
