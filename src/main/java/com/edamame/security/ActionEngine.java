package com.edamame.security;

import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.BiConsumer;

/**
 * アクション実行エンジン
 * 特定条件下でのアクション実行を管理・実行するクラス
 */
public class ActionEngine {

    private final Connection dbConnection;
    private final BiConsumer<String, String> logFunction;

    /**
     * コンストラクタ
     * @param dbConnection データベース接続
     * @param logFunction ログ出力関数
     */
    public ActionEngine(Connection dbConnection, BiConsumer<String, String> logFunction) {
        this.dbConnection = dbConnection;
        this.logFunction = logFunction != null ? logFunction :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);
    }

    /**
     * 攻撃検知時のアクション実行処理
     * @param serverName サーバー名
     * @param attackType 攻撃タイプ
     * @param ipAddress 攻撃元IPアドレス
     * @param url アクセスされたURL
     * @param timestamp タイムスタンプ
     */
    public void executeActionsOnAttackDetected(String serverName, String attackType, String ipAddress, String url, LocalDateTime timestamp) {
        try {
            // 攻撃検知条件に該当するアクションルールを取得
            List<ActionRule> rules = getMatchingRules(serverName, "attack_detected", Map.of(
                "attack_type", attackType,
                "ip_address", ipAddress,
                "url", url,
                "timestamp", timestamp.toString()
            ));

            if (rules.isEmpty()) {
                logFunction.accept("攻撃検知に対応するアクションルールが見つかりません: " + attackType, "DEBUG");
                return;
            }

            // 優先度順でルールを実行
            for (ActionRule rule : rules) {
                executeRule(rule, Map.of(
                    "server_name", serverName,
                    "attack_type", attackType,
                    "ip_address", ipAddress,
                    "url", url,
                    "timestamp", timestamp.toString()
                ));
            }

        } catch (Exception e) {
            logFunction.accept("攻撃検知時のアクション実行でエラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * 条件に一致するアクションルールを取得
     * @param serverName サーバー名
     * @param conditionType 条件タイプ
     * @param eventData イベントデータ
     * @return マッチするルールのリスト
     */
    private List<ActionRule> getMatchingRules(String serverName, String conditionType, Map<String, Object> eventData) throws SQLException {
        List<ActionRule> matchingRules = new ArrayList<>();

        String sql = """
            SELECT ar.id, ar.rule_name, ar.target_server, ar.condition_type, ar.condition_params,
                   ar.action_tool_id, ar.action_params, ar.priority,
                   at.tool_name, at.tool_type, at.config_json, at.is_enabled as tool_enabled
            FROM action_rules ar
            JOIN action_tools at ON ar.action_tool_id = at.id
            WHERE ar.is_enabled = TRUE 
              AND at.is_enabled = TRUE
              AND ar.condition_type = ?
              AND (ar.target_server = ? OR ar.target_server = '*')
            ORDER BY ar.priority ASC, ar.id ASC
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setString(1, conditionType);
            pstmt.setString(2, serverName);

            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                ActionRule rule = new ActionRule(
                    rs.getInt("id"),
                    rs.getString("rule_name"),
                    rs.getString("target_server"),
                    rs.getString("condition_type"),
                    rs.getString("condition_params"),
                    rs.getInt("action_tool_id"),
                    rs.getString("action_params"),
                    rs.getInt("priority"),
                    rs.getString("tool_name"),
                    rs.getString("tool_type"),
                    rs.getString("config_json")
                );

                // 条件パラメータをチェックしてマッチするかを確認
                if (isConditionMatching(rule, eventData)) {
                    matchingRules.add(rule);
                }
            }
        }

        return matchingRules;
    }

    /**
     * 条件がマッチするかをチェック
     * @param rule アクションルール
     * @param eventData イベントデータ
     * @return マッチする場合true
     */
    private boolean isConditionMatching(ActionRule rule, Map<String, Object> eventData) {
        try {
            if (rule.conditionParams == null || rule.conditionParams.trim().isEmpty()) {
                return true; // 条件パラメータが空の場合は常にマッチ
            }

            JSONObject conditionJson = new JSONObject(rule.conditionParams);

            switch (rule.conditionType) {
                case "attack_detected":
                    return isAttackConditionMatching(conditionJson, eventData);
                case "ip_frequency":
                    return isIpFrequencyConditionMatching(conditionJson, eventData);
                case "status_code":
                    return isStatusCodeConditionMatching(conditionJson, eventData);
                case "custom":
                    return isCustomConditionMatching(conditionJson, eventData);
                default:
                    logFunction.accept("未知の条件タイプ: " + rule.conditionType, "WARN");
                    return false;
            }

        } catch (Exception e) {
            logFunction.accept("条件マッチング処理でエラー: " + e.getMessage(), "ERROR");
            return false;
        }
    }

    /**
     * 攻撃検知条件のマッチング処理
     */
    private boolean isAttackConditionMatching(JSONObject conditionJson, Map<String, Object> eventData) {
        if (!conditionJson.has("attack_types")) {
            return true; // 攻撃タイプ指定がない場合は全て対象
        }

        JSONArray attackTypes = conditionJson.getJSONArray("attack_types");
        String currentAttackType = (String) eventData.get("attack_type");

        for (int i = 0; i < attackTypes.length(); i++) {
            if (attackTypes.getString(i).equals(currentAttackType)) {
                return true;
            }
        }

        return false;
    }

    /**
     * IP頻度条件のマッチング処理
     */
    private boolean isIpFrequencyConditionMatching(JSONObject conditionJson, Map<String, Object> eventData) {
        // 将来実装予定
        logFunction.accept("IP頻度条件は未実装です", "DEBUG");
        return false;
    }

    /**
     * ステータスコード条件のマッチング処理
     */
    private boolean isStatusCodeConditionMatching(JSONObject conditionJson, Map<String, Object> eventData) {
        // 将来実装予定
        logFunction.accept("ステータスコード条件は未実装です", "DEBUG");
        return false;
    }

    /**
     * カスタム条件のマッチング処理
     */
    private boolean isCustomConditionMatching(JSONObject conditionJson, Map<String, Object> eventData) {
        // 将来実装予定
        logFunction.accept("カスタム条件は未実装です", "DEBUG");
        return false;
    }

    /**
     * アクションルールを実行
     * @param rule アクションルール
     * @param eventData イベントデータ
     */
    private void executeRule(ActionRule rule, Map<String, Object> eventData) {
        long startTime = System.currentTimeMillis();
        String executionStatus = "success";
        String executionResult = "";

        try {
            logFunction.accept(String.format("アクションルール実行開始: %s (ツール: %s)", rule.ruleName, rule.toolName), "INFO");

            switch (rule.toolType) {
                case "mail":
                    executionResult = executeMailAction(rule, eventData);
                    break;
                case "iptables":
                    executionResult = executeIptablesAction(rule, eventData);
                    break;
                case "cloudflare":
                    executionResult = executeCloudflareAction(rule, eventData);
                    break;
                case "webhook":
                    executionResult = executeWebhookAction(rule, eventData);
                    break;
                default:
                    executionStatus = "failed";
                    executionResult = "未サポートのツールタイプ: " + rule.toolType;
                    logFunction.accept(executionResult, "ERROR");
                    break;
            }

            // 実行回数と最終実行日時を更新
            updateRuleExecutionStats(rule.id);

            logFunction.accept(String.format("アクションルール実行完了: %s", rule.ruleName), "INFO");

        } catch (Exception e) {
            executionStatus = "failed";
            executionResult = "実行エラー: " + e.getMessage();
            logFunction.accept(String.format("アクションルール実行エラー [%s]: %s", rule.ruleName, e.getMessage()), "ERROR");
        } finally {
            // 実行ログを記録
            long processingDuration = System.currentTimeMillis() - startTime;
            logExecutionResult(rule.id, (String) eventData.get("server_name"), eventData, executionStatus, executionResult, processingDuration);
        }
    }

    /**
     * メールアクションの実行
     */
    private String executeMailAction(ActionRule rule, Map<String, Object> eventData) {
        logFunction.accept("メールアクション実行: " + rule.ruleName, "INFO");

        // 現在はログ出力のみ（実際のメール送信は将来実装）
        try {
            JSONObject config = new JSONObject(rule.configJson);
            String subject = replaceVariables(config.getString("subject_template"), eventData);
            String body = replaceVariables(config.getString("body_template"), eventData);

            logFunction.accept(String.format("メール送信予定 - 件名: %s", subject), "ALERT");
            logFunction.accept(String.format("メール本文: %s", body), "DEBUG");

            return "メール送信処理完了（実際の送信は未実装）";
        } catch (Exception e) {
            throw new RuntimeException("メール設定の解析エラー: " + e.getMessage(), e);
        }
    }

    /**
     * iptablesアクションの実行
     */
    private String executeIptablesAction(ActionRule rule, Map<String, Object> eventData) {
        logFunction.accept("iptablesアクション実行: " + rule.ruleName + " (未実装)", "WARN");
        return "iptablesアクション実行予定（未実装）";
    }

    /**
     * Cloudflareアクションの実行
     */
    private String executeCloudflareAction(ActionRule rule, Map<String, Object> eventData) {
        logFunction.accept("Cloudflareアクション実行: " + rule.ruleName + " (未実装)", "WARN");
        return "Cloudflareアクション実行予定（未実装）";
    }

    /**
     * Webhookアクションの実行
     */
    private String executeWebhookAction(ActionRule rule, Map<String, Object> eventData) {
        logFunction.accept("Webhookアクション実行: " + rule.ruleName + " (未実装)", "WARN");
        return "Webhookアクション実行予定（未実装）";
    }

    /**
     * 変数置換処理
     * @param template テンプレート文字列
     * @param eventData イベントデータ
     * @return 変数置換後の文字列
     */
    private String replaceVariables(String template, Map<String, Object> eventData) {
        String result = template;
        for (Map.Entry<String, Object> entry : eventData.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    /**
     * ルールの実行統計を更新
     */
    private void updateRuleExecutionStats(int ruleId) {
        String sql = """
            UPDATE action_rules 
            SET execution_count = execution_count + 1, 
                last_executed = NOW() 
            WHERE id = ?
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setInt(1, ruleId);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logFunction.accept("ルール実行統計更新エラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * 実行結果をログテーブルに記録
     */
    private void logExecutionResult(int ruleId, String serverName, Map<String, Object> eventData,
                                  String status, String result, long durationMs) {
        String sql = """
            INSERT INTO action_execution_log 
            (rule_id, server_name, trigger_event, execution_status, execution_result, processing_duration_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement pstmt = dbConnection.prepareStatement(sql)) {
            pstmt.setInt(1, ruleId);
            pstmt.setString(2, serverName);
            pstmt.setString(3, new JSONObject(eventData).toString());
            pstmt.setString(4, status);
            pstmt.setString(5, result);
            pstmt.setInt(6, (int) durationMs);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logFunction.accept("アクション実行ログ記録エラー: " + e.getMessage(), "ERROR");
        }
    }

    /**
     * アクションルールを表すレコードクラス
     */
    private static class ActionRule {
        final int id;
        final String ruleName;
        final String targetServer;
        final String conditionType;
        final String conditionParams;
        final int actionToolId;
        final String actionParams;
        final int priority;
        final String toolName;
        final String toolType;
        final String configJson;

        ActionRule(int id, String ruleName, String targetServer, String conditionType, String conditionParams,
                  int actionToolId, String actionParams, int priority, String toolName, String toolType, String configJson) {
            this.id = id;
            this.ruleName = ruleName;
            this.targetServer = targetServer;
            this.conditionType = conditionType;
            this.conditionParams = conditionParams;
            this.actionToolId = actionToolId;
            this.actionParams = actionParams;
            this.priority = priority;
            this.toolName = toolName;
            this.toolType = toolType;
            this.configJson = configJson;
        }
    }
}
