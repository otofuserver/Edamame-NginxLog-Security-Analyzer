# AgentTcpServer.java仕様書

## 役割
- エージェントから送信されたログデータをサーバー側で受信・処理し、データベースに保存する。
- サーバー側での生ログパース・デコード処理は行わず、エージェント側でパース・デコード済みの値のみを利用する。
- 攻撃判定・ホワイトリスト判定等もデコード済み値で統一して処理する。

## 主な処理フロー
1. エージェントがログを収集し、必要なデコード・パースを実施
2. サーバーはエージェントから受信したデータをそのまま利用（再decode不要）
3. DB保存・攻撃判定・ホワイトリスト判定等は全てデコード済み値で統一

## 主なメソッド・処理
- `public void start()`
  - サーバーソケットを起動し、エージェントからのTCP接続を受け付ける。
- `private void scheduleSessionMonitoring()`
  - セッション監視スレッドをスケジューリングし、期限切れセッションを定期的にクリーンアップ。
- `private void cleanupExpiredSessions()`
  - アクティブセッションのうち、タイムアウトしたものを削除。
- `private void acceptConnections()`
  - 新規TCP接続を受け付け、スレッドプールで処理。
- `private void handleClientConnection(Socket clientSocket)`
  - クライアントごとの接続処理・認証・メッセージループを担当。
- `private String authenticateAgentWithFirstByte(DataInputStream input, DataOutputStream output, byte firstMessageType)`
  - 最初のメッセージタイプに基づきエージェント認証を実施。
- `private void handleMessage(AgentSession session, byte messageType, byte[] data)`
  - メッセージタイプごとに処理を振り分け。
- `private void handleConnectionTest(AgentSession session, byte[] data)`
  - 認証済みセッションの接続テスト応答。
- `private void handleConnectionTestDirect(DataOutputStream output)`
  - 認証不要の接続テスト応答。
- `private void handleServerRegistration(AgentSession session, byte[] data)`
  - サーバー登録要求の処理。
- `private String readStringFromStream(DataInputStream input)`
  - データストリームから文字列を読み取るユーティリティ。
- `private void handleServerUnregistration(AgentSession session, byte[] data)`
  - サーバー登録解除要求の処理。
- `private void handleHeartbeat(AgentSession session, byte[] data)`
  - ハートビート要求の処理。
- `public void handleLogBatch(AgentSession session, byte[] data)`
  - エージェントから受信したログバッチ（JSON形式）を処理し、DBに保存。
  - 受信データはすべてデコード済み値を利用。
  - ログ保存後、攻撃判定・ホワイトリスト判定を実施。
  - **rawLogLineが空の場合のみbuildParsedLogFromAgentDataを使い、サーバー側でのLogParser.parseLogLineは廃止**。
- `private void handleBlockRequest(AgentSession session, byte[] data)`
  - ブロック要求の処理。
- `private int processLogEntries(AgentSession session, List<Map<String, Object>> logs)`
  - ログバッチ内の各エントリを処理し、DB保存・攻撃判定・ModSecurity関連付け等を行う。
  - **エージェント側でデコード済み値のみを利用し、サーバー側での生ログパースは行わない**。
- `private String generateStrictRequestKey(Map<String, Object> parsedLog, String serverName)`
  - 厳密な重複チェック用リクエストキー生成。
- `private Map<String, Object> buildParsedLogFromAgentData(Map<String, Object> logData)`
  - エージェントから受信したデータからパース済みログオブジェクトを構築。
  - **URLデコードはエージェント側で済ませるため、サーバー側でのデコード処理は行わない**。
  - サーバー側での生ログ再decode・再パースは禁止。
- `private LocalDateTime parseNginxTimestamp(String timestamp)`
  - NGINXログのタイムスタンプをLocalDateTimeに変換。
- `private boolean isModSecurityRawLog(String logLine)`
  - ModSecurityログ行かどうかを判定。
- `private void processModSecurityRawLog(String rawLog, String serverName, Map<String, Object> pendingModSecInfo)`
  - ModSecurityエラーログから詳細情報を抽出。
- `private boolean checkRecentModSecurityBlock(Map<String, Object> parsedLog, Map<String, Object> pendingModSecInfo)`
  - 直近のModSecurityブロックをチェック。
- `private Long saveAccessLog(Map<String, Object> parsedLog)`
  - access_logテーブルにログを保存。
- `private void saveModSecAlert(Long accessLogId, Map<String, Object> modSecInfo, String serverName)`
  - modsec_alertsテーブルにModSecurityアラートを保存。
- `private void saveModSecAlert(Long accessLogId, Map<String, Object> modSecInfo)`
  - modsec_alertsテーブルにModSecurityアラートを保存（互換）。
- `private void processUrlAndAttackPattern(Map<String, Object> parsedLog)`
  - URL登録・攻撃パターン識別・ホワイトリスト判定を実施。
- `private String registerUrlToRegistryWithAttackType(String serverName, String method, String fullUrl, String clientIp)`
  - URL登録処理（攻撃タイプ返却・IPアドレス対応）。
- `private void executeSecurityActions(Map<String, Object> parsedLog, boolean blockedByModSec)`
  - セキュリティアクションの実行。
- `private boolean isIgnorableRequest(String url)`
  - 静的ファイル等の無視対象リクエストを判定。

## ロジック
- サーバー側での生ログパース（LogParserによるファイル直接読取）は廃止。
- URLデコードはagent受信時のみ1回だけ実施。以降の全処理はデコード済み値を使い回す。
- DB保存・攻撃判定・ホワイトリスト判定等は全てデコード済み値で統一。
- 例外は呼び出し元でハンドリング。

## 注意事項
- LogMonitorクラスは完全廃止（サーバー・エージェント両方）。

## 2025/08/10 仕様追記
- `buildParsedLogFromAgentData`は**エージェント側でデコード済みの値のみを利用し、サーバー側でのURLデコードは行わない**。
- サーバー側での`LogParser.parseLogLine`利用は廃止し、`rawLogLine`が空の場合のみ`buildParsedLogFromAgentData`を使う。
- 仕様変更に伴い、**サーバー側での生ログ再decode・再パースは禁止**。
- すべての攻撃判定・ホワイトリスト判定・DB保存は「デコード済み値」を使うこと。
- 旧仕様の「サーバー側での生ログパース・デコード」は完全廃止。
- 仕様変更内容は`specification.txt`にも反映すること。

---

### 参考：実装例
- handleLogBatch, buildParsedLogFromAgentData などをpublic/privateで実装。
- DTOはJava recordで定義。

---

## バージョン情報
- **DbServerAgentProcessing.md version**: v1.0.0
- **最終更新**: 2025-08-10
- ※このファイルを更新した場合は、必ず上記バージョン情報も更新すること
