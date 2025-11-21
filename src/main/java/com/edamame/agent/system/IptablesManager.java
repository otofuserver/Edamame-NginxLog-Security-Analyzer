package com.edamame.agent.system;

import com.edamame.agent.config.AgentConfig;
import com.edamame.agent.network.LogTransmitter;
import com.edamame.agent.util.AgentLogger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * iptables管理クラス
 * Linux iptablesを操作してIPアドレスのブロック・解除を行う
 * WindowsではWindows Firewallコマンドを使用
 *
 * @author Edamame Team
 * @version 1.1.0
 */
public class IptablesManager {

    private static final String EDAMAME_CHAIN = "EDAMAME_BLOCKS";

    private final AgentConfig config;
    private final LogTransmitter logTransmitter;
    private final Map<String, BlockRule> activeBlocks;
    private final boolean isWindows;
    private final ObjectMapper objectMapper;

    /**
     * コンストラクタ
     *
     * @param config エージェント設定
     * @param logTransmitter 統一されたLogTransmitterインスタンス
     */
    public IptablesManager(AgentConfig config, LogTransmitter logTransmitter) {
        this.config = config;
        this.logTransmitter = logTransmitter;
        this.activeBlocks = new ConcurrentHashMap<>();
        this.isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        this.objectMapper = new ObjectMapper();

        if (config.isEnableIptables()) {
            initializeFirewallChain();
        }

        AgentLogger.info("IptablesManagerを初期化しました (OS: " +
                    (isWindows ? "Windows" : "Linux") + ")");
    }

    /**
     * ファイアウォールチェーンを初期化
     */
    private void initializeFirewallChain() {
        try {
            if (isWindows) {
                // Windows Firewallの初期化は不要
                AgentLogger.info("Windows Firewallを準備しました");
            } else {
                // Linuxでiptablesチェーンを作成（Dockerコンテナ対応）
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", "iptables -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
                Process process = pb.start();
                int exitCode = process.waitFor();
                BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                StringBuilder errorOutput = new StringBuilder();
                String errorLine;
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                String errorStr = errorOutput.toString().toLowerCase();
                if (exitCode == 0) {
                    AgentLogger.debug("コマンド実行成功: sh -c iptables -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
                    AgentLogger.info("iptablesチェーン " + EDAMAME_CHAIN + " を初期化しました");
                } else if (errorStr.contains("chain already exists") || (exitCode == 1 && errorStr.isBlank())) {
                    AgentLogger.info("iptablesチェーン " + EDAMAME_CHAIN + " は既に存在します。既存チェーンを利用します。");
                } else if (exitCode == 4 && errorStr.isBlank()) {
                    AgentLogger.error("iptablesコマンドが権限不足で失敗しました。root権限で実行してください: sh -c iptables -t filter -N " + EDAMAME_CHAIN + " 2>/dev/null");
                } else {
                    AgentLogger.error("iptablesチェーン " + EDAMAME_CHAIN + " の初期化に失敗しました。コマンド実行結果: exitCode=" + exitCode + ", error=" + errorOutput);
                }

                // EDAMAME_BLOCKSチェーン末尾に-j RETURNがなければ追加
                ProcessBuilder listPb = new ProcessBuilder("sh", "-c", "iptables -t filter -S " + EDAMAME_CHAIN);
                Process listProcess = listPb.start();
                BufferedReader listReader = new BufferedReader(new InputStreamReader(listProcess.getInputStream()));
                String lastRule = null;
                String line;
                while ((line = listReader.readLine()) != null) {
                    lastRule = line;
                }
                if (lastRule == null || !lastRule.trim().endsWith("-j RETURN")) {
                    // 末尾にRETURNがなければ追加
                    boolean added = executeCommand(new String[]{"sh", "-c", "iptables -A " + EDAMAME_CHAIN + " -j RETURN"});
                    if (added) {
                        AgentLogger.info("iptablesチェーン " + EDAMAME_CHAIN + " の末尾に-j RETURNを追加しました");
                    } else {
                        AgentLogger.warn("iptablesチェーン " + EDAMAME_CHAIN + " の末尾に-j RETURNの追加に失敗しました");
                    }
                } else {
                    AgentLogger.debug("iptablesチェーン " + EDAMAME_CHAIN + " の末尾には既に-j RETURNがあります");
                }
            }
        } catch (Exception e) {
            AgentLogger.error("ファイアウォールチェーンの初期化に失敗しました: " + e.getMessage());
        }
    }

    /**
     * ブロック要求を処理
     */
    public void processBlockRequests() {
        if (!config.isEnableIptables()) {
            return;
        }

        try {
            String blockRequestsJson = logTransmitter.fetchBlockRequests();

            // 空の応答やnullチェック
            if (blockRequestsJson == null || blockRequestsJson.trim().isEmpty() || "[]".equals(blockRequestsJson.trim())) {
                // データなしは正常状態
                return;
            }

            // JSONの形式を判定して適切にパース
            List<BlockRequest> requests;
            try {
                // まず配列形式として解析を試行
                TypeReference<List<BlockRequest>> arrayTypeRef = new TypeReference<List<BlockRequest>>() {};
                requests = objectMapper.readValue(blockRequestsJson, arrayTypeRef);
            } catch (Exception arrayParseException) {
                try {
                    // 配列解析に失敗した場合、Map形式として解析を試行
                    TypeReference<Map<String, List<BlockRequest>>> mapTypeRef = 
                        new TypeReference<Map<String, List<BlockRequest>>>() {};
                    Map<String, List<BlockRequest>> response = objectMapper.readValue(blockRequestsJson, mapTypeRef);
                    requests = response.getOrDefault("requests", new ArrayList<>());
                } catch (Exception mapParseException) {
                    AgentLogger.warn("ブロック要求のJSON解析に失敗しました。配列エラー: " + arrayParseException.getMessage() +
                                   ", Map解析エラー: " + mapParseException.getMessage());
                    AgentLogger.debug("受信したJSON: " + blockRequestsJson);
                    return;
                }
            }

            if (!requests.isEmpty()) {
                AgentLogger.debug("ブロック要求を " + requests.size() + " 件処理します");
                for (BlockRequest request : requests) {
                    processBlockRequest(request);
                }
            }

        } catch (Exception e) {
            AgentLogger.warn("ブロック要求の処理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * 個別のブロック要求を処理
     */
    private void processBlockRequest(BlockRequest request) {
        try {
            LocalDateTime now = LocalDateTime.now();

            if ("block".equals(request.getAction())) {
                blockIpAddress(request.getIpAddress(), request.getDurationMinutes());
            } else if ("unblock".equals(request.getAction())) {
                unblockIpAddress(request.getIpAddress());
            }

        } catch (Exception e) {
            AgentLogger.error("ブロック要求処理中にエラーが発生しました: " + e.getMessage());
        }
    }

    /**
     * IPアドレスをブロック
     */
    private void blockIpAddress(String ipAddress, int durationMinutes) {
        try {
            if (activeBlocks.containsKey(ipAddress)) {
                AgentLogger.debug("IPアドレス " + ipAddress + " は既にブロック済みです");
                return;
            }

            LocalDateTime expiryTime = LocalDateTime.now().plusMinutes(durationMinutes);
            String command = buildBlockCommand(ipAddress);

            if (executeCommand(command)) {
                activeBlocks.put(ipAddress, new BlockRule(ipAddress, expiryTime));
                AgentLogger.info("IPアドレス " + ipAddress + " をブロックしました (期限: " +
                    expiryTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + ")");
            }

        } catch (Exception e) {
            AgentLogger.error("IPアドレス " + ipAddress + " のブロックに失敗しました: " + e.getMessage());
        }
    }

    /**
     * IPアドレスのブロックを解除
     */
    private void unblockIpAddress(String ipAddress) {
        try {
            if (!activeBlocks.containsKey(ipAddress)) {
                AgentLogger.debug("IPアドレス " + ipAddress + " はブロックされていません");
                return;
            }

            String command = buildUnblockCommand(ipAddress);

            if (executeCommand(command)) {
                activeBlocks.remove(ipAddress);
                AgentLogger.info("IPアドレス " + ipAddress + " のブロックを解除しました");
            }

        } catch (Exception e) {
            AgentLogger.error("IPアドレス " + ipAddress + " のブロック解除に失敗しました: " + e.getMessage());
        }
    }

    /**
     * ブロックコマンドを構築
     */
    private String buildBlockCommand(String ipAddress) {
        if (isWindows) {
            return "netsh advfirewall firewall add rule name=\"EDAMAME_BLOCK_" + ipAddress +
                   "\" dir=in action=block remoteip=" + ipAddress;
        } else {
            return "iptables -I " + EDAMAME_CHAIN + " -s " + ipAddress + " -j DROP";
        }
    }

    /**
     * ブロック解除コマンドを構築
     */
    private String buildUnblockCommand(String ipAddress) {
        if (isWindows) {
            return "netsh advfirewall firewall delete rule name=\"EDAMAME_BLOCK_" + ipAddress + "\"";
        } else {
            return "iptables -D " + EDAMAME_CHAIN + " -s " + ipAddress + " -j DROP";
        }
    }

    /**
     * コマンドを実行（配列あり）
     * 権限不足やコマンド失敗時は標準エラー出力・終了コードを詳細にログ出力し、root権限でない場合の警告も明示
     */
    private boolean executeCommand(String[] command) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                AgentLogger.debug("コマンド実行成功: " + String.join(" ", command));
                return true;
            } else {
                BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                AgentLogger.warn("コマンド実行エラー: " + String.join(" ", command) + " -> " + errorOutput + " (終了コード: " + exitCode + ")");
                // Linuxでiptables失敗時は権限不足の可能性を明示
                if (!isWindows && (errorOutput.toString().toLowerCase().contains("permission denied") ||
                                   errorOutput.toString().toLowerCase().contains("operation not permitted") ||
                                   errorOutput.toString().toLowerCase().contains("must be run as root") ||
                                   errorOutput.toString().toLowerCase().contains("you must be root") ||
                                   errorOutput.toString().toLowerCase().contains("permission denied") ||
                        errorOutput.toString().toLowerCase().contains("not permitted"))) {
                    AgentLogger.error("iptablesコマンドが権限不足で失敗しました。root権限で実行してください: " + String.join(" ", command) + "\nエラー内容: " + errorOutput.toString());
                } else if (!isWindows && exitCode != 0) {
                    AgentLogger.warn("iptablesコマンドが失敗しました。root権限でない場合は動作しません: " + String.join(" ", command) + "\nエラー内容: " + errorOutput.toString());
                }
                return false;
            }
        } catch (Exception e) {
            AgentLogger.error("コマンド実行に失敗しました: " + String.join(" ", command) + " -> " + e.getMessage());
            if (!isWindows) {
                AgentLogger.warn("iptablesコマンド実行時に例外が発生しました。root権限でない場合は動作しません");
            }
            return false;
        }
    }

    /**
     * コマンドを実行（文字列1本）
     * 権限不足やコマンド失敗時は標準エラー出力・終了コードを詳細にログ出力し、root権限でない場合の警告も明示
     */
    private boolean executeCommand(String command) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            if (isWindows) {
                builder.command("cmd.exe", "/c", command);
            } else {
                builder.command("sh", "-c", command);
            }
            Process process = builder.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                AgentLogger.debug("コマンド実行成功: " + String.join(" ", command));
                return true;
            } else {
                BufferedReader errorReader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()));
                String errorLine;
                StringBuilder errorOutput = new StringBuilder();
                while ((errorLine = errorReader.readLine()) != null) {
                    errorOutput.append(errorLine).append("\n");
                }
                String errorStr = errorOutput.toString().toLowerCase();
                AgentLogger.warn("コマンド実行エラー: " + command + " -> " + errorOutput.toString() + " (終了コード: " + exitCode + ")");
                // Linuxでiptables失敗時は権限不足の可能性を明示
                if (!isWindows && (errorStr.contains("permission denied") || errorStr.contains("operation not permitted") || errorStr.contains("Permission denied") || errorStr.contains("must be run as root") || errorStr.contains("you must be root") || errorStr.contains("not permitted"))) {
                    AgentLogger.error("iptablesコマンドが権限不足で失敗しました。root権限で実行してください: " + command + "\nエラー内容: " + errorOutput.toString());
                } else if (!isWindows && exitCode != 0) {
                    AgentLogger.warn("iptablesコマンドが失敗しました。root権限でない場合は動作しません: " + command + "\nエラー内容: " + errorOutput.toString());
                }
                return false;
            }
        } catch (Exception e) {
            AgentLogger.error("コマンド実行に失敗しました: " + command + " -> " + e.getMessage());
            if (!isWindows) {
                AgentLogger.warn("iptablesコマンド実行時に例外が発生しました。root権限でない場合は動作しません");
            }
            return false;
        }
    }

    /**
     * 期限切れのブロックを削除
     */
    public void cleanupExpiredBlocks() {
        LocalDateTime now = LocalDateTime.now();
        List<String> expiredIps = new ArrayList<>();

        for (Map.Entry<String, BlockRule> entry : activeBlocks.entrySet()) {
            if (entry.getValue().getExpiryTime().isBefore(now)) {
                expiredIps.add(entry.getKey());
            }
        }

        for (String ip : expiredIps) {
            unblockIpAddress(ip);
        }
    }

    /**
     * ブロック要求データクラス
     */
    public static class BlockRequest {
        private String ipAddress;
        private String action;
        private int durationMinutes;
        private String reason;

        // ...existing constructors and getters/setters...
        public BlockRequest() {}

        public String getIpAddress() { return ipAddress; }
        public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public int getDurationMinutes() { return durationMinutes; }
        public void setDurationMinutes(int durationMinutes) { this.durationMinutes = durationMinutes; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    /**
     * アクティブなブロックルールデータクラス
     */
    private static class BlockRule {
        private final String ipAddress;
        private final LocalDateTime expiryTime;

        public BlockRule(String ipAddress, LocalDateTime expiryTime) {
            this.ipAddress = ipAddress;
            this.expiryTime = expiryTime;
        }

        public LocalDateTime getExpiryTime() { return expiryTime; }
    }
}
