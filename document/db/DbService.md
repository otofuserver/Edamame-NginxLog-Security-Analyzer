# DbService.java 仕様書

## 役割
- データベース操作の統合サービスクラス。
- DbSessionを内部で管理し、従来のConnection引数なしでDB操作を提供。
- 既存のDb*クラス（DbSelect, DbUpdate, DbRegistry, DbSchema, DbInitialData, DbDelete）の全メソッドを統合し、Connection引数を完全に排除。
- AutoCloseableインターフェースを実装し、try-with-resourcesでの安全なリソース管理を提供。

## 主なメソッド（v2.1.0 - ModSecurityアラート照合機能強化）

### コンストラクタ・接続管理
- `public DbService(String url, Properties properties)`
  - コンストラクタ。内部でDbSessionインスタンスを作成し、データベース接続を管理。
- `public boolean isConnected()`
  - 接続状態をチェック。接続中の場合true。
- `public void setAutoCommit(boolean autoCommit) throws SQLException`
  - AutoCommitモードを設定。内部のDbSessionに委譲。
- `public void commit() throws SQLException`
  - 手動コミット。内部のDbSessionに委譲。
- `public void rollback() throws SQLException`
  - 手動ロールバック。内部のDbSessionに委譲。
- `public void close()`
  - リソースのクリーンアップ。内部のDbSessionを安全にクローズ。

### SELECT操作（DbSelect統合）
- `public Optional<DbSelect.ServerInfo> selectServerInfoByName(String serverName) throws SQLException`
  - サーバー名でサーバー情報を取得。Connection引数なしでDbSelect.selectServerInfoByNameを呼び出し。
- `public boolean existsServerByName(String serverName) throws SQLException`
  - サーバー名で存在有無を取得。Connection引数なしでDbSelect.existsServerByNameを呼び出し。
- `public List<Map<String, Object>> selectPendingBlockRequests(String registrationId, int limit) throws SQLException`
  - 指定registrationIdのpendingなブロック要求リストを取得。
- `public Map<String, Object> selectWhitelistSettings() throws SQLException`
  - settingsテーブルからホワイトリスト設定を取得。
- `public boolean existsUrlRegistryEntry(String serverName, String method, String fullUrl) throws SQLException`
  - url_registryテーブルに指定のエントリが存在するか判定。
- `public Boolean selectIsWhitelistedFromUrlRegistry(String serverName, String method, String fullUrl) throws SQLException`
  - url_registryテーブルからis_whitelistedを取得。
- `public List<Map<String, Object>> selectRecentAccessLogsForModSecMatching(int minutes) throws SQLException`
  - **[v2.1.0新機能]** 最近の指定分数以内のアクセスログを取得（ModSecurity照合用）。
  - `blocked_by_modsec = false`の未処理ログのみを抽出し、AgentTcpServerの定期的なアラート照合に使用。

### UPDATE操作（DbUpdate統合）
- `public void updateServerInfo(String serverName, String description, String logPath)`
  - サーバー情報を更新。例外は内部でキャッチしログ出力。
- `public void updateServerLastLogReceived(String serverName)`
  - サーバーの最終ログ受信時刻を更新。例外は内部でキャッチしログ出力。
- `public int updateAgentHeartbeat(String registrationId)`
  - エージェントのハートビートを更新。戻り値は更新された行数。
- `public int updateAgentLogStats(String registrationId, int logCount)`
  - エージェントのログ処理統計を更新。戻り値は更新された行数。
- `public int deactivateAgent(String registrationId)`
  - 特定エージェントをinactive状態に変更。戻り値は更新された行数。
- `public void deactivateAllAgents()`
  - 全アクティブエージェントをinactive状態に変更。例外は内部でキャッチしログ出力。
- `public int updateUrlWhitelistStatus(String serverName, String method, String fullUrl)`
  - URLをホワイトリスト状態に更新。戻り値は更新された行数。
- `public int updateAccessLogModSecStatus(Long accessLogId, boolean blockedByModSec) throws SQLException`
  - **[v2.1.0強化]** access_logのModSecurityブロック状態を更新。定期的なアラート照合での使用が増加。

### INSERT/REGISTRY操作（DbRegistry統合）
- `public void registerOrUpdateServer(String serverName, String description, String logPath)`
  - サーバー情報を登録または更新。例外は内部でキャッチしログ出力。
- `public String registerOrUpdateAgent(Map<String, Object> serverInfo)`
  - エージェントサーバーを登録または更新。成功時は登録ID、失敗時はnull。
- `public Long insertAccessLog(Map<String, Object> parsedLog)`
  - access_logテーブルにログを保存。成功時は登録されたID、失敗時はnull。
- `public boolean registerUrlRegistryEntry(String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType)`
  - url_registryテーブルに新規URLを登録。成功時はtrue、失敗時はfalse。
- `public void insertModSecAlert(Long accessLogId, Map<String, Object> modSecInfo)`
  - **[v2.1.0強化]** modsec_alertsテーブ��にModSecurityアラートを保存。
  - 型安全性を向上し、`severity`フィールドのInteger/String両対応を実現。

### スキーマ・初期化操作（DbSchema・DbInitialData統合）
- `public void syncAllTablesSchema() throws SQLException`
  - 全テーブルのスキーマ自動同期。DbSchema.syncAllTablesSchemaを呼び出し。
- `public void initializeDefaultData(String appVersion) throws SQLException`
  - 初期データを挿入。DbInitialData.initializeDefaultDataを呼び出し。

### メンテナンス操作（DbDelete統合）
- `public void runLogCleanupBatch()`
  - ログ自動削除バッチ処理。例外は内部でキャッチしログ出力。

### トランザクション操作
- `public void executeInTransaction(Runnable operations) throws SQLException`
  - トランザクション内で複数のDB操作を実行。内部のDbSessionに委譲。

## ロジック（v2.1.0更新）
- **Connection管理の完全隠蔽**: 呼び出し元はConnection オブジェクトを一切意識する必要がない。
- **統一されたAPI**: 既存のDb*クラスの全メソッドを一つのクラスで提供。
- **自動エラーハンドリング**: SQLException を適切にログ出力し、デフォルト値を返却。
- **内部委譲**: 実際のDB操作は既存のDb*クラスのstaticメソッドに委譲し、Connection引数のみを自動で渡す。
- **例外処理の統一**: SELECT系メソッドはSQLExceptionをスロー、UPDATE/INSERT系メソッドは例外をキャッチしてログ出力後にデフォルト値を返却。
- **トランザクション管理**: executeInTransaction()で複数操作の原子性を保証。
- **リソース管理**: AutoCloseableでDbSessionの適切なクリーンアップを保証。
- **ModSecurityアラート照合**: 定期的な照合機能により、時間差で到着するアラートとログの関連付けを強化。

## エラーハンドリング方針
### SELECT系メソッド
- SQLExceptionをそのままスローし、呼び出し元で処理。
- データが見つからない場合はOptional.empty()、false、nullを適切に返却。

### UPDATE/INSERT系メソッド
- SQLExceptionを内部でキャッチし、ERRORログを出力。
- デフォルト値（0、false、null等）を返却して処理を継続可能にする。
- 戻り値で処理結果を判定可能（更新行数、成功/失敗フラグ等）。

### スキーマ・初期化系メソッド
- SQLExceptionをそのままスローし、アプリケーション起動時の致命的エラーとして扱う。

## 使用例（v2.1.0強化版）

### ModSecurityアラート定期照合での使用（v2.1.0新機能）
```java
// AgentTcpServerでの定期的なアラート照合処理
try (DbService dbService = new DbService(url, properties)) {
    // 最近5分以内の未処理アクセスログを取得
    List<Map<String, Object>> recentAccessLogs = 
        dbService.selectRecentAccessLogsForModSecMatching(5);
    
    for (Map<String, Object> accessLog : recentAccessLogs) {
        Long accessLogId = (Long) accessLog.get("id");
        String serverName = (String) accessLog.get("server_name");
        String method = (String) accessLog.get("method");
        String fullUrl = (String) accessLog.get("full_url");
        LocalDateTime accessTime = (LocalDateTime) accessLog.get("access_time");
        
        // ModSecurityアラートキューから一致するアラートを検索
        List<ModSecurityAlert> matchingAlerts = 
            modSecurityQueue.findMatchingAlerts(serverName, method, fullUrl, accessTime);
        
        if (!matchingAlerts.isEmpty()) {
            // access_logのblocked_by_modsecをtrueに更新
            dbService.updateAccessLogModSecStatus(accessLogId, true);
            
            // 一致したアラートをmodsec_alertsテーブルに保存
            for (ModSecurityAlert alert : matchingAlerts) {
                Map<String, Object> alertData = new HashMap<>();
                alertData.put("rule_id", alert.ruleId());
                alertData.put("message", alert.message());
                alertData.put("data_value", alert.dataValue());
                alertData.put("severity", alert.severity()); // Integer/String両対応
                alertData.put("server_name", alert.serverName());
                alertData.put("raw_log", alert.rawLog());
                alertData.put("detected_at", alert.detectedAt().toString());
                
                dbService.insertModSecAlert(accessLogId, alertData);
            }
        }
    }
}
```

### 基本的な使用方法
```java
// Connection引数が完全に不要
try (DbService db = new DbService(url, props)) {
    // SELECT操作
    Optional<ServerInfo> info = db.selectServerInfoByName("web-server-01");
    boolean exists = db.existsServerByName("web-server-01");
    
    // UPDATE操作
    db.updateServerLastLogReceived("web-server-01");
    int updated = db.updateAgentHeartbeat("agent-123");
    
    // INSERT操作
    Long accessLogId = db.insertAccessLog(logData);
    String agentId = db.registerOrUpdateAgent(agentInfo);
}
```

### トランザクション操作
```java
try (DbService db = new DbService(url, props)) {
    // 複数操作をトランザクションで実行
    db.executeInTransaction(() -> {
        Long accessLogId = db.insertAccessLog(logData);
        db.updateServerLastLogReceived("web-server-01");
        db.registerUrlRegistryEntry("web-server-01", "GET", "/api/users", false, "none");
    });
}
```

## 注意事項（v2.1.0更新）
- **既存Db*クラスとの併用**: 既存のDb*クラスは下位互換性のため残存。段階的移行が可能。
- **スレッドセーフティ**: 同一インスタンスを複数スレッドで共有しないこと。
- **リソース管理**: try-with-resourcesでの使用を強く推奨。
- **例外処理**: SELECT系とUPDATE/INSERT系で例外処理方針が異なることに注意。
- **トランザクション境界**: executeInTransaction()内では他のトランザクション操作を呼び出さないこと。
- **ModSecurityアラート照合**: `selectRecentAccessLogsForModSecMatching`は定期的な照合処理専用メソッド。
- **型安全性**: `insertModSecAlert`メソッドは`severity`フィールドでInteger/String両方の型に対応。

## パフォーマンス考慮事項（v2.1.0更新）
- **接続再利用**: 同一DbServiceインスタンス内ではConnection を再利用。
- **定期照合の最適化**: `selectRecentAccessLogsForModSecMatching`は最大1000件に制限し、パフォーマン��を維持。
- **遅延初期化**: 実際にDB操作が必要になるまで接続を確立しない。
- **自動リトライ**: 接続失敗時の自動リトライにより可用性を向上。

## セキュリティ要件
- **Connection情報の隠蔽**: 呼び出し元にConnection オブジェクトを直接公開しない。
- **トランザクション管理**: 自動的なcommit/rollbackによりデータ整合性を保証。
- **例外情報の適切な処理**: SQLExceptionの詳細を適切にログ出力し、セキュリティ情報の漏洩を防止。

## バージョン履歴
- **v2.1.0** (2025-08-13): ModSecurityアラート照合機能を強化、selectRecentAccessLogsForModSecMatchingメソッドを追加、insertModSecAlertの型安全性を向上
- **v2.0.0** (2025-01-11): Connection引数を完全廃止、DbService専用に統一。互換性なし一気切り替え完了
- **v1.1.0** (2025-01-11): ビルドエラー修正完了
- **v1.0.0**: 初期実装