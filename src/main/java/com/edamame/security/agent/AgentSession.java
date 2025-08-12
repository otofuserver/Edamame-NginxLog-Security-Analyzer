package com.edamame.security.agent;


import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * エージェントセッション管理クラス
 * 個々のエージェント接続を管理し、通信状態を追跡する
 *
 * @author Edamame Team
 * @version 1.0.0
 */
public class AgentSession {

    private final String agentName;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private String registrationId;
    private LocalDateTime lastActivity;
    private volatile boolean active;

    /**
     * コンストラクタ
     *
     * @param agentName エージェント名
     * @param socket ソケット接続
     * @param input 入力ストリーム
     * @param output 出力ストリーム
     */
    public AgentSession(String agentName, Socket socket, DataInputStream input, DataOutputStream output) {
        this.agentName = agentName;
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.registrationId = null;
        this.lastActivity = LocalDateTime.now();
        this.active = true;
    }

    /**
     * エージェント名を取得
     *
     * @return エージェント名
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * 登録IDを取得
     *
     * @return 登録ID
     */
    public String getRegistrationId() {
        return registrationId;
    }

    /**
     * 登録IDを設定
     *
     * @param registrationId 登録ID
     */
    public void setRegistrationId(String registrationId) {
        this.registrationId = registrationId;
    }

    /**
     * 最終アクティビティ時刻を取得
     *
     * @return 最終アクティビティ時刻
     */
    public LocalDateTime getLastActivity() {
        return lastActivity;
    }

    /**
     * 最終アクティビティ時刻をミリ秒で取得
     *
     * @return 最終アクティビティ時刻（ミリ秒）
     */
    public long getLastActivityMillis() {
        return lastActivity.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /**
     * 最終アクティビティ時刻を更新
     */
    public void updateLastActivity() {
        this.lastActivity = LocalDateTime.now();
    }

    /**
     * セッションがアクティブかどうかを確認
     *
     * @return アクティブな場合true
     */
    public boolean isActive() {
        return active && !socket.isClosed();
    }

    /**
     * レスポンスを送信
     *
     * @param responseCode レスポンスコード
     * @param message メッセージ
     * @throws IOException 送信エラー
     */
    public synchronized void sendResponse(byte responseCode, String message) throws IOException {
        if (!isActive()) {
            throw new IOException("Session is not active");
        }

        try {
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);

            output.writeByte(responseCode);
            output.writeInt(messageBytes.length);
            output.write(messageBytes);
            output.flush();

            updateLastActivity();

        } catch (IOException e) {
            active = false;
            throw e;
        }
    }

    /**
     * セッションを閉じる
     */
    public void close() {
        active = false;

        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            // ログ出力は呼び出し元で実施
        }

        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException e) {
            // ログ出力は呼び出し元で実施
        }

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // ログ出力は呼び出し元で実施
        }
    }

    /**
     * セッション情報の文字列表現
     *
     * @return セッション情報
     */
    @Override
    public String toString() {
        return String.format("AgentSession[name=%s, registrationId=%s, active=%s, lastActivity=%s]",
                agentName, registrationId, active, lastActivity);
    }
}
