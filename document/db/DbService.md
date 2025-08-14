# DbService.java 仕様書

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- データベース操作の統合サービスクラス（Static版）
- グローバルなDbSessionを管理し、staticメソッドでDB操作を提供
- 既存のDb*クラス（DbSelect, DbUpdate, DbRegistry, DbSchema, DbInitialData, DbDelete）の全メソッドを統合し、Connection引数を完全に排除
- **1つのDBにのみアクセスすることを前提とした設計**
- アプリケーション全体で単一のデータベース接続を共有

## 設計思想

### Static化の利点
- **グローバル接続管理**: アプリケーション全体で単一のDbSessionを共有
- **簡潔なAPI**: `DbService.methodName()`で直接呼び出し可能
- **初期化一回限り**: アプリケーション起動時の一度の初期化で全機能利用可能
- **リソース効率**: 不要なインスタンス生成を回避

### 設計制約
- **シングルトンパターン**: グローバルなDbSessionインスタンスを1つのみ管理
- **初期化必須**: 使用前に`initialize()`の呼び出しが必要
- **スレッドセーフティ**: synchronized による初期化・シャットダウンの同期化

## 主要メソッド詳細

### 初期化・ライフサイクル管理

#### `initialize(String url, Properties properties)`
- **機能**: DbServiceの初期化（アプリケーション起動時に1回だけ呼び出し）
- **引数**: データベースURL、接続プロパティ
- **例外**: IllegalStateException（既に初期化済みの場合）
- **スレッドセーフ**: synchronized により同期化

```java
public static synchronized void initialize(String url, Properties properties) {
    if (initialized) {
        throw new IllegalStateException("DbService is already initialized");
    }
    globalSession = new DbSession(url, properties);
    initialized = true;
}
```

#### `shutdown()`
- **機能**: DbServiceのシャットダウン（アプリケーション終了時に呼び出し）
- **処理**: globalSessionのクローズ、初期化状態リセット
- **スレッドセーフ**: synchronized により同期化

#### `isInitialized()`
- **機能**: 初期化状態���確認
- **戻り値**: boolean（初期化済みの場合true）

#### `checkInitialized()` - プライベートメソッド
- **機能**: 内部使用の初期化チェック
- **例外**: IllegalStateException（未初期化の場合）

### SELECT操作（DbSelectに完全委譲）

#### `selectServerInfoByName(String serverName)`
- **機能**: サーバー名でサーバー情報を取得
- **戻り値**: Optional<DbSelect.ServerInfo>
- **委譲先**: DbSelect.selectServerInfoByName(globalSession, serverName)

#### `existsServerByName(String serverName)`
- **機能**: サーバー名で存在有無を判定
- **戻り値**: boolean
- **委譲先**: DbSelect.existsServerByName(globalSession, serverName)

#### `selectPendingBlockRequests(String registrationId, int limit)`
- **機能**: 指定エージェントのpendingなブロック要求リストを取得
- **戻り値**: List<Map<String, Object>>
- **委譲先**: DbSelect.selectPendingBlockRequests(globalSession, registrationId, limit)

#### `selectWhitelistSettings()`
- **機能**: settingsテーブルからホワイトリスト設定を取得
- **戻り値**: Map<String, Object>
- **委譲先**: DbSelect.selectWhitelistSettings(globalSession)

#### `existsUrlRegistryEntry(String serverName, String method, String fullUrl)`
- **機能**: url_registryテーブルに指定URLエントリが存在するか判定
- **戻り値**: boolean
- **委譲先**: DbSelect.existsUrlRegistryEntry(globalSession, serverName, method, fullUrl)

#### `selectIsWhitelistedFromUrlRegistry(String serverName, String method, String fullUrl)`
- **機能**: url_registryテーブルからis_whitelisted値を取得
- **戻り値**: Boolean（null許可）
- **委譲先**: DbSelect.selectIsWhitelistedFromUrlRegistry(globalSession, serverName, method, fullUrl)

#### `selectRecentAccessLogsForModSecMatching(int minutes)`
- **機���**: ModSecurity照合用の最近のアクセスログを取得
- **戻り値**: List<Map<String, Object>>
- **用途**: AgentTcpServerでの定期的なアラート照合処理
- **委譲先**: DbSelect.selectRecentAccessLogsForModSecMatching(globalSession, minutes)

### UPDATE操作（DbUpdateに完全委譲）

#### `updateServerInfo(String serverName, String description, String logPath)`
- **機能**: サーバー情報の更新
- **委譲先**: DbUpdate.updateServerInfo(globalSession, serverName, description, logPath)

#### `updateServerLastLogReceived(String serverName)`
- **機能**: サーバーの最終ログ受信時刻のみ更新
- **委譲先**: DbUpdate.updateServerLastLogReceived(globalSession, serverName)

#### `updateAgentHeartbeat(String registrationId)`
- **機能**: エージェントのハートビート更新
- **戻り値**: int（更新された行数）
- **委譲先**: DbUpdate.updateAgentHeartbeat(globalSession, registrationId)

#### `updateAgentLogStats(String registrationId, int logCount)`
- **機能**: エージェントのログ処理統計更新
- **戻り値**: int（更新された行数）
- **委譲先**: DbUpdate.updateAgentLogStats(globalSession, registrationId, logCount)

#### `updateAccessLogModSecStatus(Long accessLogId, boolean blockedByModSec)`
- **機能**: access_logのModSecurityブロック状態更新
- **戻り値**: int（更新された行数）
- **重要性**: ModSecurityキューベース関連付けシステムの核心機能
- **委譲先**: DbUpdate.updateAccessLogModSecStatus(globalSession, accessLogId, blockedByModSec)

#### `deactivateAgent(String registrationId)`
- **機能**: 特定エージェントのinactive状態への変更
- **戻り値**: int（更新された行数）
- **委譲先**: DbUpdate.deactivateAgent(globalSession, registrationId)

#### `deactivateAllAgents()`
- **機能**: 全アクティブエージェントのinactive状態への変更
- **委譲先**: DbUpdate.deactivateAllAgents(globalSession)

#### `updateUrlWhitelistStatus(String serverName, String method, String fullUrl)`
- **機能**: URLのホワイトリスト状態更新
- **戻り値**: int（更新された行数）
- **委譲先**: DbUpdate.updateUrlWhitelistStatus(globalSession, serverName, method, fullUrl)

### INSERT/REGISTRY操作（DbRegistryに完全委譲）

#### `registerOrUpdateServer(String serverName, String description, String logPath)`
- **機能**: サーバー情報の登録または更新
- **委譲先**: DbRegistry.registerOrUpdateServer(globalSession, serverName, description, logPath)

#### `registerOrUpdateAgent(Map<String, Object> serverInfo)`
- **機能**: エージェントサーバーの登録または更新
- **戻り値**: String（登録ID、失敗時はnull）
- **委譲先**: DbRegistry.registerOrUpdateAgent(globalSession, serverInfo)

#### `insertAccessLog(Map<String, Object> parsedLog)`
- **機能**: access_logテーブルへのログ保存
- **戻り値**: Long（登録されたID、失敗時はnull）
- **委譲先**: DbRegistry.insertAccessLog(globalSession, parsedLog)

#### `registerUrlRegistryEntry(String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType)`
- **機能**: url_registryテーブルへの新規URL登録
- **戻り値**: boolean（成功時true）
- **委譲先**: DbRegistry.registerUrlRegistryEntry(globalSession, serverName, method, fullUrl, isWhitelisted, attackType)

#### `insertModSecAlert(Long accessLogId, Map<String, Object> modSecInfo)`
- **機能**: modsec_alertsテーブルへのModSecurityアラート保存
- **委譲先**: DbRegistry.insertModSecAlert(globalSession, accessLogId, modSecInfo)

### スキーマ・初期化操作

#### `syncAllTablesSchema()`
- **機能**: 全テーブルのスキーマ自動同期
- **委譲先**: DbSchema.syncAllTablesSchema(globalSession)

#### `initializeDefaultData(String appVersion)`
- **機能**: 初期データの挿入
- **委譲先**: DbInitialData.initializeDefaultData(globalSession, appVersion)

### メンテナンス操作

#### `runLogCleanupBatch()`
- **機能**: ログ自動削除バッチ処理
- **例外処理**: 内部でキャッチし、上位でハンドリング
- **委譲先**: DbDelete.runLogCleanupBatch(globalSession)

### トランザクション・接続管理

#### `executeInTransaction(Runnable operations)`
- **機能**: トランザクシ��ン内での複数DB操作実行
- **引数**: Runnable（ラムダ式）
- **委譲先**: globalSession.executeInTransaction()

#### `getConnection()`
- **機能**: 内部DbSessionから直接Connection取得
- **用途**: 既存クラスとの互換性維持
- **戻り値**: Connection インスタンス

#### `isConnected()`
- **機能**: 接続状態チェック
- **戻り値**: boolean（接続中の場合true）

#### `setAutoCommit(boolean autoCommit)`
- **機能**: AutoCommitモード設定
- **委譲先**: globalSession.setAutoCommit()

#### `commit()` / `rollback()`
- **機能**: 手動コミット・ロールバック
- **委譲先**: globalSession.commit() / globalSession.rollback()

## 使用パターン

### アプリケーション起動時の初期化
```java
// メインクラスでの初期化
public static void main(String[] args) {
    Properties props = loadDatabaseProperties();
    
    try {
        // DbService初期化
        DbService.initialize(databaseUrl, props);
        
        // スキーマ同期
        DbService.syncAllTablesSchema();
        
        // 初期データ投入
        DbService.initializeDefaultData("2.1.0");
        
        // アプリケーション処理開始
        startApplication();
        
    } finally {
        // 終了時のクリーンアップ
        DbService.shutdown();
    }
}
```

### 基本的なDB操作
```java
// SELECT操作
Optional<ServerInfo> serverInfo = DbService.selectServerInfoByName("web-server-01");
boolean exists = DbService.existsServerByName("web-server-01");
List<Map<String, Object>> blockRequests = DbService.selectPendingBlockRequests("agent-123", 10);

// UPDATE操作
DbService.updateServerInfo("web-server-01", "本番Webサーバー", "/var/log/nginx/");
int updated = DbService.updateAgentHeartbeat("agent-123");

// INSERT操作
DbService.registerOrUpdateServer("web-server-01", "Webサーバー", "/var/log/nginx/");
Long accessLogId = DbService.insertAccessLog(parsedLogMap);
```

### トランザクション処理
```java
// 複数操作を1つのトランザクションで実行
DbService.executeInTransaction(() -> {
    // 複数のDB操作
    DbService.registerOrUpdateServer(serverName, description, logPath);
    DbService.updateServerLastLogReceived(serverName);
    DbService.insertAccessLog(parsedLogMap);
    // すべて成功時に自動コミット、例外時は自動ロールバック
});
```

### ModSecurity照合処理での活用
```java
// AgentTcpServerでの定期照合処理
public void performPeriodicAlertMatching() {
    try {
        // 最近のアクセスログを取得
        List<Map<String, Object>> recentLogs = 
            DbService.selectRecentAccessLogsForModSecMatching(5);
        
        for (Map<String, Object> log : recentLogs) {
            Long accessLogId = (Long) log.get("id");
            
            // ModSecurityキューから一致するアラートを検索
            List<ModSecAlert> matchingAlerts = findMatchingAlerts(log);
            
            if (!matchingAlerts.isEmpty()) {
                // ブロック状態を更新
                int updated = DbService.updateAccessLogModSecStatus(accessLogId, true);
                
                // アラート保存
                for (ModSecAlert alert : matchingAlerts) {
                    DbService.insertModSecAlert(accessLogId, alert.toMap());
                }
            }
        }
    } catch (SQLException e) {
        AppLogger.error("ModSecurity照合処理エラー: " + e.getMessage());
    }
}
```

## エラーハンドリング

### 例外処理方針
- **SELECT系メソッド**: SQLExceptionをそのままスロー、呼び出し元で処理
- **UPDATE/INSERT系メソッド**: SQLExceptionをそのままスロー、呼び出し元で処理
- **初期化チェック**: 全メソッドでIllegalStateExceptionによる未初期化検出

### 初期化状態管理
```java
private static void checkInitialized() {
    if (!initialized || globalSession == null) {
        throw new IllegalStateException("DbService is not initialized. Call DbService.initialize() first.");
    }
}
```

## パフォーマンス考慮事項
- **単一接続共有**: グローバルDbSessionによる効率的なリソース利用
- **遅延初期化**: DbSessionの実際の接続は初回使用時まで遅延
- **完全委譲**: 実際の処理は既存のDb*クラスに委譲し、オーバーヘッド最小化
- **トランザクション最適化**: executeInTransaction()による適切な範囲制御

## セキュリティ対策
- **接続情報保護**: Properties経由での安全な管理
- **SQLインジェクション対策**: 各Db*クラスでのPreparedStatement使用
- **リソースリーク防止**: shutdown()による確実なクリーンアップ
- **アクセス制御**: staticメソッドによる統一的なアクセス管理

## 注意事項・制限事項
- **初期化必須**: 使用前に必ずinitialize()を呼び出すこと
- **シングルトン設計**: 複数データベースへの同時接続は非対応
- **スレッドセーフティ**: 初期化・シャットダウン以外はDbSessionに依存
- **例外処理必須**: 全メソッドでSQLExceptionの適切なハンドリングが必要

## バージョン履歴
- **v1.0.0**: 初期実装（インスタンスベース、AutoCloseable対応）
- **v2.0.0**: DbSessionとの完全統合
- **v2.1.0**: **Static化、グローバルDbSession管理、初期化・シャットダウン機能追加**

---

## 実装参考

### アプリケーション初期化パターン
```java
public class Application {
    public static void main(String[] args) {
        try {
            // 1. DbService初期化
            DbService.initialize(DATABASE_URL, properties);
            
            // 2. 必要な初期化処理
            if (DbService.isInitialized()) {
                DbService.syncAllTablesSchema();
                DbService.initializeDefaultData("2.1.0");
            }
            
            // 3. アプリケーション開始
            startMainApplication();
            
        } catch (Exception e) {
            AppLogger.error("アプリケーション起動エラー: " + e.getMessage());
        } finally {
            // 4. クリーンアップ
            DbService.shutdown();
        }
    }
}
```

### DB操作の標準パターン
```java
try {
    // 単発操作
    Optional<ServerInfo> info = DbService.selectServerInfoByName(serverName);
    int updated = DbService.updateAgentHeartbeat(registrationId);
    
    // トランザクション操作
    DbService.executeInTransaction(() -> {
        DbService.insertAccessLog(logData);
        DbService.updateServerLastLogReceived(serverName);
    });
    
} catch (SQLException e) {
    AppLogger.error("DB操作エラー: " + e.getMessage());
    // エラーハンドリング
} catch (IllegalStateException e) {
    AppLogger.error("DbService未初期化: " + e.getMessage());
    // 初期化エラーハンドリング
}
```
