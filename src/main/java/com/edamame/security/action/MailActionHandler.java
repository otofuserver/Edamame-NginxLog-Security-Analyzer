package com.edamame.security.action;

import com.edamame.security.tools.AppLogger;
import org.json.JSONObject;

import javax.mail.*;
import javax.mail.internet.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.edamame.security.db.DbService.*;

/**
 * メールアクションを担当するハンドラ
 *
 * ActionEngine から切り出されたメール送信・SMTP設定読み込み・接続チェック等のロジックを実装します。
 *
 * 注意: データベースのユーザ/ロール構成については本実装内で想定を置いています（users テーブルに email, role, is_enabled がある等）。
 * 必要に応じて SQL をプロジェクトの実際のスキーマに合わせて調整してください。
 */
public class MailActionHandler {

    private JSONObject smtpConfig;
    private boolean smtpConfigLoaded = false;
    private final Map<String, SmtpCheckResult> smtpCheckCache = new HashMap<>();

    /**
     * コンストラクタ
     */
    public MailActionHandler() {
        initializeSmtpConfig();
    }

    /**
     * ActionEngine から呼ばれるメールアクション実行メソッド
     *
     * @param configJson アクションツールの config_json（JSON文字列）
     * @param eventData イベントデータ（変数置換などに使用）
     * @return 実行結果の説明文字列
     */
    public String executeMailAction(String configJson, Map<String, Object> eventData) {
        AppLogger.log("メールアクション実行（委譲）", "INFO");

        try {
            JSONObject config = new JSONObject(configJson);

            JSONObject smtpCfg = getSmtpConfig().getJSONObject("smtp");
            JSONObject defaults = getSmtpConfig().getJSONObject("defaults");

            String smtpHost = smtpCfg.getString("host");
            int smtpPort = smtpCfg.getInt("port");

            String fromEmail = config.optString("from_email", defaults.getString("from_email"));
            String fromName = config.optString("from_name", defaults.getString("from_name"));
            String toEmail = config.getString("to_email");

            // SMTP接続チェック
            if (!isSmtpServerAvailable(smtpHost, smtpPort)) {
                String warningMsg = String.format("SMTP接続不可: %s:%d - メール送信をスキップします", smtpHost, smtpPort);
                AppLogger.log(warningMsg, "WARN");
                return "SMTP接続不可のためスキップ: " + smtpHost + ":" + smtpPort;
            }

            String subjectTemplate = config.optString("subject_template", "(no-subject)");
            String bodyTemplate = config.optString("body_template", "");

            String subject = replaceVariables(subjectTemplate, eventData);
            String body = replaceVariables(bodyTemplate, eventData);

            // SMTPプロパティ構築
            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));
            props.put("mail.smtp.auth", smtpCfg.getBoolean("auth_required") ? "true" : "false");
            props.put("mail.smtp.timeout", String.valueOf(smtpCfg.getInt("timeout") * 1000));
            props.put("mail.smtp.connectiontimeout", String.valueOf(smtpCfg.getInt("connection_timeout") * 1000));

            String security = smtpCfg.getString("security");
            if ("STARTTLS".equals(security)) {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            } else if ("SSL".equals(security)) {
                props.put("mail.smtp.ssl.enable", "true");
            }

            Session session;
            if (smtpCfg.getBoolean("auth_required")) {
                final String username = smtpCfg.getString("username");
                final String password = smtpCfg.getString("password");
                session = Session.getInstance(props, new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });
            } else {
                session = Session.getInstance(props);
            }

            if (smtpCfg.getBoolean("enable_debug")) {
                session.setDebug(true);
            }

            // メッセージ作成と送信
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromEmail, fromName));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject(subject);
            message.setText(body);
            message.setSentDate(new java.util.Date());

            Transport.send(message);

            AppLogger.log(String.format("メール送信成功: %s", toEmail), "INFO");
            return "メール送信完了: " + toEmail;

        } catch (Exception e) {
            AppLogger.log(String.format("メール送信失敗: %s", e.getMessage()), "ERROR");
            return "メール送信失敗: " + e.getMessage();
        }
    }

    /**
     * 直接メールアドレスを指定して送信する（同期）
     *
     * @param fromEmail 送信元メール
     * @param fromName 送信元名
     * @param toEmail 送信先メール
     * @param subject 件名
     * @param body 本文
     * @return 実行結果メッセージ
     */
    public String sendToAddress(String fromEmail, String fromName, String toEmail, String subject, String body) {
        try {
            JSONObject smtp = getSmtpConfig().getJSONObject("smtp");
            Properties props = new Properties();
            String smtpHost = smtp.getString("host");
            int smtpPort = smtp.getInt("port");
            boolean authRequired = smtp.getBoolean("auth_required");

            // 送信前にSMTP情報をログ出力
            AppLogger.log(String.format("sendToAddress: SMTP host=%s port=%d auth_required=%s to=%s", smtpHost, smtpPort, authRequired, toEmail), "INFO");

            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));

            if (authRequired) {
                final String username = smtp.getString("username");
                final String password = smtp.getString("password");
                Session session = Session.getInstance(props, new Authenticator() {
                    protected PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication(username, password);
                    }
                });
                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(fromEmail, fromName));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
                msg.setSubject(subject);
                msg.setText(body);
                Transport.send(msg);
            } else {
                Session session = Session.getInstance(props);
                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress(fromEmail, fromName));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
                msg.setSubject(subject);
                msg.setText(body);
                Transport.send(msg);
            }
            AppLogger.log("直接送信成功: " + toEmail, "INFO");
            return "送信成功: " + toEmail;
        } catch (Exception e) {
            // 失敗時は詳細をログ出力（スタックトレースの最初の行を含める）
            String errMsg = e.getMessage();
            AppLogger.log("直接送信失敗: " + errMsg, "ERROR");
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                e.printStackTrace(pw);
                AppLogger.log("sendToAddress exception: " + sw.toString(), "DEBUG");
            } catch (Exception ex) {
                // ignore
            }
            return "送信失敗: " + errMsg;
        }
    }

    /**
     * 指定ロールのユーザーに送信（上位ロールは含めない）
     *
     * @param role 送信対象ロール名
     * @param fromEmail 送信元メール
     * @param fromName 送信元名
     * @param subject 件名
     * @param body 本文
     * @return 実行結果要約
     */
    public String sendToRole(String role, String fromEmail, String fromName, String subject, String body) {
        List<String> recipients = new ArrayList<>();

        // 1) まず users_roles + roles.role_name を利用する一般的な多対多クエリを試みる
        String sqlJoin = "SELECT DISTINCT u.email FROM users u JOIN users_roles ur ON u.id = ur.user_id JOIN roles r ON ur.role_id = r.id WHERE r.role_name = ? AND u.is_active = 1";
        try (PreparedStatement pstmt = getConnection().prepareStatement(sqlJoin)) {
            pstmt.setString(1, role);
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                recipients.add(rs.getString("email"));
            }
        } catch (SQLException e) {
            AppLogger.log("sendToRole: users_roles 経由のクエリでエラー（フォールバックを試行）: " + e.getMessage(), "DEBUG");
        }

        // 2) 結果が空なら、users.role_id の単一参照方式でフォールバック（互換性確保）
        if (recipients.isEmpty()) {
            String sqlFallback = "SELECT u.email FROM users u JOIN roles r ON u.role_id = r.id WHERE r.role_name = ? AND u.is_active = 1";
            try (PreparedStatement pstmt = getConnection().prepareStatement(sqlFallback)) {
                pstmt.setString(1, role);
                ResultSet rs = pstmt.executeQuery();
                while (rs.next()) {
                    recipients.add(rs.getString("email"));
                }
            } catch (SQLException e) {
                AppLogger.log("sendToRole DBエラー（フォールバック含む）: " + e.getMessage(), "ERROR");
                return "DBエラー: " + e.getMessage();
            }
        }

        if (recipients.isEmpty()) {
            AppLogger.log("sendToRole: 対象ユーザーが見つかりません: " + role, "WARN");
            return "対象ユーザーが見つかりません: " + role;
        }

        int success = 0;
        for (String to : recipients) {
            String res = sendToAddress(fromEmail, fromName, to, subject, body);
            if (res.startsWith("送信成功")) success++;
        }

        return String.format("送信先 %d 件中 %d 件送信成功", recipients.size(), success);
    }

    /**
     * 指定ロールおよびその上位ロールのユーザーに送信する（ロール階層を roles テーブルで管理している想定）
     *
     * roles テーブルの想定カラム: name, parent_role
     * MySQL 8 の再帰CTE を利用して上位ロールを列挙します。
     *
     * @param role 送信対象ロール名
     * @param fromEmail 送信元メール
     * @param fromName 送信元名
     * @param subject 件名
     * @param body 本文
     * @return 実行結果要約
     */
    public String sendToRoleIncludingHigherRoles(String role, String fromEmail, String fromName, String subject, String body) {
        List<String> recipients = new ArrayList<>();

        try {
            // 1) 対象ロールの id を取得
            Integer targetRoleId = null;
            String getRoleIdSql = "SELECT id FROM roles WHERE role_name = ?";
            try (PreparedStatement ps = getConnection().prepareStatement(getRoleIdSql)) {
                ps.setString(1, role);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) targetRoleId = rs.getInt("id");
            }

            if (targetRoleId == null) {
                AppLogger.log("sendToRoleIncludingHigherRoles: 指定ロールが見つかりません: " + role, "WARN");
                return "対象ロールが見つかりません: " + role;
            }

            // 2) roles.inherited_roles(JSON) を参照して、上位ロール（自分を継承しているロール）をたどる（親を探索）
            Set<Integer> roleIds = new HashSet<>();
            Deque<Integer> queue = new ArrayDeque<>();
            roleIds.add(targetRoleId);
            queue.add(targetRoleId);

            while (!queue.isEmpty()) {
                int childId = queue.poll();
                // 親ロールを検索：inherited_roles に childId が含まれているロールを親とみなす
                // ここでは roles.inherited_roles が数値配列([1,2,3]) の場合と文字列配列(["1","2"]) の場合の両方に対応するため
                // JSON_CONTAINS(inherited_roles, CAST(? AS JSON)) OR JSON_CONTAINS(inherited_roles, CONCAT('\"', ?, '\"')) を使用します。
                String parentSearchSql = "SELECT id FROM roles WHERE JSON_CONTAINS(inherited_roles, CAST(? AS JSON)) OR JSON_CONTAINS(inherited_roles, CONCAT('\"', ?, '\"'))";
                try (PreparedStatement ps = getConnection().prepareStatement(parentSearchSql)) {
                    // パラメータを2回バインド（数値JSON と 文字列JSON の両方にマッチさせるため）
                    ps.setString(1, String.valueOf(childId));
                    ps.setString(2, String.valueOf(childId));
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        int parentId = rs.getInt("id");
                        if (!roleIds.contains(parentId)) {
                            roleIds.add(parentId);
                            queue.add(parentId);
                        }
                    }
                } catch (SQLException e) {
                    // JSON カラムが無い・JSON_CONTAINS 非対応などのケースではループを中止してフォールバックへ
                    AppLogger.log("sendToRoleIncludingHigherRoles: 役割継承検索でエラー、フォールバックを試行します: " + e.getMessage(), "DEBUG");
                    roleIds.clear();
                    roleIds.add(targetRoleId);
                    break;
                }
            }

            // 3) 得られた roleIds を使って users を取得（まず users_roles 中間テーブル経由）
            if (!roleIds.isEmpty()) {
                // ロールID一覧をログ出力してデバッグしやすくする
                AppLogger.log("sendToRoleIncludingHigherRoles: 収集された roleIds = " + roleIds.toString(), "DEBUG");

                String inClause = String.join(",", Collections.nCopies(roleIds.size(), "?"));
                String sql = "SELECT DISTINCT u.email FROM users u JOIN users_roles ur ON u.id = ur.user_id WHERE ur.role_id IN (" + inClause + ") AND u.is_active = 1";
                try (PreparedStatement ps = getConnection().prepareStatement(sql)) {
                    int idx = 1;
                    for (Integer id : roleIds) ps.setInt(idx++, id);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) recipients.add(rs.getString("email"));
                } catch (SQLException e) {
                    AppLogger.log("sendToRoleIncludingHigherRoles: users_roles 経由での取得に失敗しました、フォールバックを試行: " + e.getMessage(), "DEBUG");
                    // フォールバック: users.role_id を利用する単一路線
                    String fallbackSql = "SELECT u.email FROM users u WHERE u.role_id IN (" + inClause + ") AND u.is_active = 1";
                    try (PreparedStatement fps = getConnection().prepareStatement(fallbackSql)) {
                        int idx = 1;
                        for (Integer id : roleIds) fps.setInt(idx++, id);
                        ResultSet frs = fps.executeQuery();
                        while (frs.next()) recipients.add(frs.getString("email"));
                    } catch (SQLException fe) {
                        AppLogger.log("sendToRoleIncludingHigherRoles DBエラー（フォールバックも失敗）: " + fe.getMessage(), "ERROR");
                        return "DBエラー: " + fe.getMessage();
                    }
                }

                // 追加フォールバック: users_roles が正常でも結果が空の場合は users.role_id を試す
                if (recipients.isEmpty()) {
                    AppLogger.log("sendToRoleIncludingHigherRoles: users_roles 経由で送信対象が見つかりませんでした、users.role_id で再検索します", "DEBUG");
                    String fallbackSql2 = "SELECT u.email FROM users u WHERE u.role_id IN (" + inClause + ") AND u.is_active = 1";
                    try (PreparedStatement fps2 = getConnection().prepareStatement(fallbackSql2)) {
                        int idx = 1;
                        for (Integer id : roleIds) fps2.setInt(idx++, id);
                        ResultSet frs2 = fps2.executeQuery();
                        while (frs2.next()) recipients.add(frs2.getString("email"));
                    } catch (SQLException fe2) {
                        AppLogger.log("sendToRoleIncludingHigherRoles DBエラー（フォールバック2失敗）: " + fe2.getMessage(), "ERROR");
                        return "DBエラー: " + fe2.getMessage();
                    }
                }

                // 取得した受信者をログ出力
                AppLogger.log("sendToRoleIncludingHigherRoles: 送信対象メールアドレス数=" + recipients.size() + ", list=" + recipients.toString(), "DEBUG");
            }

        } catch (Exception e) {
            AppLogger.log("sendToRoleIncludingHigherRoles で例外: " + e.getMessage(), "ERROR");
            return "エラー: " + e.getMessage();
        }

        if (recipients.isEmpty()) {
            AppLogger.log("sendToRoleIncludingHigherRoles: 対象ユーザーが見つかりません: " + role, "WARN");
            return "対象ユーザーが見つかりません: " + role;
        }

        int success = 0;
        for (String to : recipients) {
            String res = sendToAddress(fromEmail, fromName, to, subject, body);
            if (res.startsWith("送信成功")) success++;
        }

        return String.format("送信先 %d 件中 %d 件送信成功（上位ロール含む）", recipients.size(), success);
    }

    // ================= SMTP 設定読み込み・チェック周り =================

    private JSONObject createDefaultSmtpConfig() {
        JSONObject defaultConfig = new JSONObject();
        JSONObject smtp = new JSONObject();
        smtp.put("host", "localhost");
        smtp.put("port", 25);
        smtp.put("auth_required", false);
        smtp.put("username", "");
        smtp.put("password", "");
        smtp.put("security", "NONE");
        smtp.put("timeout", 30);
        smtp.put("connection_timeout", 15);
        smtp.put("enable_debug", false);

        JSONObject defaults = new JSONObject();
        defaults.put("from_email", "noreply@example.com");
        defaults.put("from_name", "Edamame Security Analyzer");

        defaultConfig.put("smtp", smtp);
        defaultConfig.put("defaults", defaults);
        return defaultConfig;
    }

    private void initializeSmtpConfig() {
        try {
            this.smtpConfig = loadSmtpConfigFromFile();
            this.smtpConfigLoaded = true;
            AppLogger.log("SMTP設定の初期化が完了しました", "INFO");
        } catch (Exception e) {
            AppLogger.log("SMTP設定の初期化に失敗しました。デフォルト設定を使用します: " + e.getMessage(), "WARN");
            this.smtpConfig = createDefaultSmtpConfig();
            this.smtpConfigLoaded = true;
        }
    }

    private JSONObject getSmtpConfig() {
        if (!smtpConfigLoaded) initializeSmtpConfig();
        return this.smtpConfig;
    }

    private JSONObject loadSmtpConfigFromFile() throws Exception {
        String[] configPaths = {
            "container/config/smtp_config.json",
            "/app/config/smtp_config.json",
            "config/smtp_config.json",
            "smtp_config.json"
        };

        for (String configPath : configPaths) {
            java.io.File configFile = new java.io.File(configPath);
            if (configFile.exists()) {
                AppLogger.log("SMTP設定ファイル読み込み成功: " + configPath, "INFO");
                try (java.io.FileReader reader = new java.io.FileReader(configFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    StringBuilder content = new StringBuilder();
                    char[] buffer = new char[1024];
                    int length;
                    while ((length = reader.read(buffer)) != -1) {
                        content.append(buffer, 0, length);
                    }
                    return new JSONObject(content.toString());
                } catch (Exception e) {
                    AppLogger.log("SMTP設定ファイル読み込みエラー: " + configPath + " - " + e.getMessage(), "WARN");
                }
            }
        }

        throw new Exception("SMTP設定ファイルが見つかりません");
    }

    private boolean isSmtpServerAvailable(String host, int port) {
        String cacheKey = host + ":" + port;
        long currentTime = System.currentTimeMillis();

        if (smtpCheckCache.containsKey(cacheKey)) {
            SmtpCheckResult cachedResult = smtpCheckCache.get(cacheKey);
            if (currentTime - cachedResult.timestamp < 300000) {
                if (!cachedResult.available) AppLogger.log(String.format("SMTP接続チェック（キャッシュ）: %s:%d - 接続不可", host, port), "DEBUG");
                return cachedResult.available;
            }
        }

        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port), 15000);
            smtpCheckCache.put(cacheKey, new SmtpCheckResult(true, currentTime));
            AppLogger.log(String.format("SMTP接続チェック成功: %s:%d", host, port), "DEBUG");
            return true;
        } catch (java.net.SocketTimeoutException e) {
            AppLogger.log(String.format("SMTP接続タイムアウト %s:%d (15秒)", host, port), "DEBUG");
            smtpCheckCache.put(cacheKey, new SmtpCheckResult(false, currentTime));
            return false;
        } catch (java.net.ConnectException e) {
            AppLogger.log(String.format("SMTP接続拒否 %s:%d - %s", host, port, e.getMessage()), "DEBUG");
            smtpCheckCache.put(cacheKey, new SmtpCheckResult(false, currentTime));
            return false;
        } catch (Exception e) {
            AppLogger.log(String.format("SMTP接続チェック失敗 %s:%d - %s", host, port, e.getMessage()), "DEBUG");
            smtpCheckCache.put(cacheKey, new SmtpCheckResult(false, currentTime));
            return false;
        }
    }

    private static class SmtpCheckResult {
        final boolean available;
        final long timestamp;

        SmtpCheckResult(boolean available, long timestamp) {
            this.available = available;
            this.timestamp = timestamp;
        }
    }

    // ヘルパー: 変数置換（ActionEngine と同様の挙動を維持）
    private String replaceVariables(String template, Map<String, Object> eventData) {
        String result = template;
        for (Map.Entry<String, Object> entry : eventData.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }
}

