# AgentTcpServer.java仕様書

## 役割
- エージェントから送信されたログデータをサーバー側で受信・処理し、データベースに保存する。
- サーバー側での生ログパース・デコード処理は行わず、エージェント側でパース・デコード済みの値のみを利用する。
- 攻撃判定・ホワイトリスト判定等もデコード済み値で統一して処理する。
- **ModSecurity関連処理**：外部注入されたModSecurityキューを使用してアラート管理・照合機能を提供。

## ModSecurity関連機能（v3.0.0リファクタリング）
- **ModSecurityQueue**: 外部から注入されたアラートキューを使用して一時保存・時間・URL一致による関連付け
- **ModSecHandler**: ModSecurityログ解析・情報抽出（staticメソッドとして使用）
- **キュー管理**: `NginxLogToMysql`クラスでModSecurityキューとタスクを一元管理
- **定期的アラート照合**: `ModSecHandler.startPeriodicAlertMatching()`で5秒間隔の照合タスクを開始
- **時間差対応**: ModSecurityアラートが先に到着してもアクセスログと適切に関連付け

## 主な処理フロー
1. エージェントがログを収集し、必要なデコード・パースを実施
2. サーバーはエージェントから受信したデータをそのまま利用（再decode不要）
3. **ModSecurityアラート処理**: `ModSecHandler.extractModSecInfo()`で情報抽出、外部キューに追加
4. **アラートキュー照合**: 注入されたModSecurityキューから一致するアラートを検索
5. DB保存・攻撃判定・ホワイトリスト判定等は全てデコード済み値で統一

## 主なメソッド・処理

### コンストラクタ（v3.0.0変更）
- `public AgentTcpServer(DbService dbService, ModSecurityQueue modSecurityQueue)`
  - **必須パラメータ**: ModSecurityキューを外部から注入
  - **依存性注入**: キュー管理の責務を外部に委譲
- `public AgentTcpServer(int port, DbService dbService, ModSecurityQueue modSecurityQueue)`
  - ポート指定版コンストラクタ

### 基本メソッド
- `public void start()`
  - サーバーソケットを起動し、エージェントからのTCP接続を受け付ける。
  - **ModSecurityタスク管理は外部で実行済み**。
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
  - ログ保存後、攻撃判定・ホワイトリスト判定・**外部ModSecurityキューとの照合**を実施。
  - **rawLogLineが空の場合のみbuildParsedLogFromAgentDataを使い、サーバー側でのLogParser.parseLogLineは廃止**。
- `private void handleBlockRequest(AgentSession session, byte[] data)`
  - ブロック要求の処理。
- `private int processLogEntries(AgentSession session, List<Map<String, Object>> logs)`
  - ログバッチ内の各エントリを処理し、DB保存・攻撃判定・**外部ModSecurityキューとの関連付け**等を行う。
  - **エージェント側でデコード済み値のみを利用し、サーバー側での生ログパースは行わない**。
  - **外部ModSecurityキューからの一致検索・保存処理を含む**。

### ユーティリティメソッド
- `private String generateStrictRequestKey(Map<String, Object> parsedLog, String serverName)`
  - 厳密な重複チェック用リクエストキー生成。
- `private Map<String, Object> buildParsedLogFromAgentData(Map<String, Object> logData)`
  - エージェントから受信したデータからパース済みログオブジェクトを構築。
  - **URLデコードはエージェント側で済ませるため、サーバー側でのデコード処理は行わない**。
  - サーバー側での生ログ再decode・再パースは禁止。
- `private LocalDateTime parseNginxTimestamp(String timestamp)`
  - NGINXログのタイムスタンプをLocalDateTimeに変換。
- `private void processUrlAndAttackPattern(Map<String, Object> parsedLog)`
  - URL登録・攻撃パターン識別・ホワイトリスト判定を実施。
- `private void executeSecurityActions(Map<String, Object> parsedLog, boolean blockedByModSec)`
  - セキュリティアクションの実行。
- `private boolean isIgnorableRequest(String url)`
  - 静的ファイル等の無視対象リクエストを判定。

## ロジック（v3.0.0更新）
- サーバー側での生ログパース（LogParserによるファイル直接読取）は廃止。
- URLデコードはagent受信時のみ1回だけ実施。以降の全処理はデコード済み値を使い回す。
- DB保存・攻撃判定・ホワイトリスト判定等は全てデコード済み値で統一。
- **ModSecurityアラート管理**: 外部から注入されたModSecurityキューを使用。
- **キュー管理の責務分離**: キュ���の初期化・タスク管理は`NginxLogToMysql`クラスが担当。
- **定期的アラート照合**: 外部で管理されるタスクによる自動照合。
- **時間差対応**: ModSecurityアラートが先に到着してもアクセスログとの関連付けを保証。
- 例外は呼び出し元でハンドリング。

## アーキテクチャ（v3.0.0変更）

### 依存関係
```
NginxLogToMysql (メインクラス)
├── ModSecurityQueue (作成・管理)
├── ModSecHandler (タスク開始)
└── AgentTcpServer (キューを注入)
```

### 責務分離
- **NginxLogToMysql**: アプリケーション全体の初期化・ModSecurityキュー管理
- **ModSecurityQueue**: アラートキューの管理・クリーンアップタスク
- **ModSecHandler**: アラート処理・照合ロジック・定期タスク
- **AgentTcpServer**: TCP通信・エージェント管理・ログ処理

## 注意事項
- LogMonitorクラスは完全廃止（サーバー・エージェント両方）。
- **ModSecurityキューは外部から注入される必須パラメータ**。
- **キュー管理・タスク管理の機能は削除済み**。
- **定期的アラート照合は外部で管理される**。

## 2025/08/13 仕様追記（v3.0.0）
- すべての攻撃判定・ホワイトリスト判定・DB保存は「デコード済み値」を使うこと。
- **ModSecurityアラート管理アーキテクチャを大幅改善**：
  - キュー管理の責務を`NginxLogToMysql`クラスに移管
  - `AgentTcpServer`は外部から注入された��ューを使用する方式に変更
  - 関心の分離と依存関係の明確化を実現
- 仕様変更内容は`specification.txt`にも反映すること。

## バージョン情報
- **AgentTcpServer.md version**: v3.0.0
- **最終更新**: 2025-08-13
- **主要変更**: ModSecurityアラート管理アーキテクチャの改善・責務分離・依存性注入パターンの導入
- ※このファイルを更新した場合は、必ず上記バージョン情報も更新すること
