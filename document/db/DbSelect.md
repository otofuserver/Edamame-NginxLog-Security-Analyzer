# DbSelect.java 仕様書

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- データベースからの検索・SELECT処理専用クラス
- 各種テーブルの検索・集計・条件抽出を実装
- サーバー・エージェント・URL登録等のSELECT系処理を集約
- **UPDATE系処理はDbUpdate.javaに完全移譲**

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド詳細

### `selectServerInfoByName(DbSession dbSession, String serverName)`
- **機能**: サーバー名でサーバー情報を取得
- **戻り値**: Optional<ServerInfo>（存在しない場合はOptional.empty()）
- **照合順序**: utf8mb4_unicode_ci対応

```sql
SELECT id, server_description, log_path 
FROM servers 
WHERE server_name = ? COLLATE utf8mb4_unicode_ci
```

### `existsServerByName(DbSession dbSession, String serverName)`
- **機能**: サーバー名で存在有無を判定
- **戻り値**: boolean（存在すればtrue）
- **照合順序**: utf8mb4_unicode_ci対応

```sql
SELECT COUNT(*) 
FROM servers 
WHERE server_name = ? COLLATE utf8mb4_unicode_ci
```

### `selectPendingBlockRequests(DbSession dbSession, String registrationId, int limit)`
- **機能**: 指定エージェントのpendingなブロック要求リストを取得
- **戻り���**: List<Map<String, Object>>（ブロック要求情報のリスト）
- **フィールドマッピング**: 
  - `request_id` → `"id"`
  - `ip_address` → `"ipAddress"`
  - `duration` → `"duration"`
  - `reason` → `"reason"`
  - `chain_name` → `"chainName"`

```sql
SELECT request_id, ip_address, duration, reason, chain_name
FROM agent_block_requests
WHERE registration_id = ? AND status = 'pending'
ORDER BY created_at ASC
LIMIT ?
```

### `selectWhitelistSettings(DbSession dbSession)`
- **機能**: settingsテーブルからホワイトリスト設定を取得
- **戻り値**: Map<String, Object>（whitelist_mode、whitelist_ip）
- **設定ID**: 固定値1のレコードを取得

```sql
SELECT whitelist_mode, whitelist_ip
FROM settings
WHERE id = 1
```

### `existsUrlRegistryEntry(DbSession dbSession, String serverName, String method, String fullUrl)`
- **機能**: url_registryテーブルに指定URLエントリが存在するか判定
- **戻り値**: boolean（存在すればtrue）
- **判定条件**: server_name + method + full_urlの組み合わせ

```sql
SELECT COUNT(*) FROM url_registry
WHERE server_name = ? AND method = ? AND full_url = ?
```

### `selectIsWhitelistedFromUrlRegistry(DbSession dbSession, String serverName, String method, String fullUrl)`
- **機能**: url_registryテーブルからis_whitelisted値を取得
- **戻り値**: Boolean（存在しない場合はnull）
- **用途**: ホワイトリスト状態の確認

```sql
SELECT is_whitelisted FROM url_registry
WHERE server_name = ? AND method = ? AND full_url = ?
```

### `selectRecentAccessLogsForModSecMatching(DbSession dbSession, int minutes)`
- **機能**: ModSecurity照合用の最近のアクセスログを取得
- **戻り値**: List<Map<String, Object>>（アクセスログ情報のリスト、最大1000件）
- **対象**: blocked_by_modsec = falseの未処理ログのみ
- **並び順**: access_time降順
- **用途**: 時間差で到着するModSecurityアラートとの照合

```sql
SELECT id, server_name, method, full_url, access_time
FROM access_log
WHERE access_time >= NOW() - INTERVAL ? MINUTE
AND blocked_by_modsec = false
ORDER BY access_time DESC
LIMIT 1000
```

## DTOクラス

### `ServerInfo record`
```java
public record ServerInfo(int id, String description, String logPath) {}
```
- **用途**: サーバー情報の格納・受け渡し
- **Java record**: 不変性・型安全性を保証

## エラーハンドリング

### 例外処理階層
1. **各メソッド**: SQLException → RuntimeExceptionでラップ
2. **DbSession**: Connection管理・トランザクション管理を自動化
3. **結果処理**: データが見つからない場合の適切な戻り値設定

### 戻り値パターン
- **存在判定**: boolean（true/false）
- **単一レコード**: Optional<T>（存在しない場合はOptional.empty()）
- **複数レコード**: List<Map<String, Object>>（空の場合は空のList）
- **設定値**: Map<String, Object>（設定が存在しない場合は空のMap）
- **nullable値**: Boolean（存在しない場合はnull）

## DbSessionでの使用方法
```java
// 標準的な��用パターン
try {
    // サーバー情報取得
    Optional<DbSelect.ServerInfo> serverInfo = 
        DbSelect.selectServerInfoByName(dbSession, "web-server-01");
    
    if (serverInfo.isPresent()) {
        DbSelect.ServerInfo info = serverInfo.get();
        System.out.println("Server ID: " + info.id());
        System.out.println("Description: " + info.description());
        System.out.println("Log Path: " + info.logPath());
    }
    
    // 存在確認
    boolean exists = DbSelect.existsServerByName(dbSession, "web-server-01");
    
    // ブロック要求取得
    List<Map<String, Object>> blockRequests = 
        DbSelect.selectPendingBlockRequests(dbSession, "agent-123", 10);
    
    // ホワイトリスト設定取得
    Map<String, Object> whitelistSettings = 
        DbSelect.selectWhitelistSettings(dbSession);
    
    // URL存在確認
    boolean urlExists = DbSelect.existsUrlRegistryEntry(
        dbSession, "web-server-01", "GET", "/api/users");
    
    // ホワイトリスト状態取得
    Boolean isWhitelisted = DbSelect.selectIsWhitelistedFromUrlRegistry(
        dbSession, "web-server-01", "GET", "/api/users");
    
    // ModSecurity照合用の最近のアクセスログ取得
    List<Map<String, Object>> recentLogs = 
        DbSelect.selectRecentAccessLogsForModSecMatching(dbSession, 5);
        
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

## ModSecurity定期照合での使用例

### AgentTcpServerでの定期的なアラート照合処理
```java
public void performPeriodicAlertMatching() {
    try {
        // 最近5分以内の未処理アクセスログを取得
        List<Map<String, Object>> recentAccessLogs = 
            DbSelect.selectRecentAccessLogsForModSecMatching(dbSession, 5);
        
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
                // 一致したアラートをデータベースに保存
                DbUpdate.updateAccessLogModSecStatus(dbSession, accessLogId, true);
                
                // アラート詳細をmodsec_alertsテーブルに保存
                for (ModSecurityAlert alert : matchingAlerts) {
                    DbRegistry.insertModSecAlert(dbSession, accessLogId, alert.toMap());
                }
            }
        }
    } catch (SQLException e) {
        AppLogger.error("ModSecurity照合処理でエラー: " + e.getMessage());
    }
}
```

## パフォーマンス考慮事項
- **インデックス活用**: server_name、method、full_url、access_time等の検索条件にインデックス必須
- **LIMIT句使用**: 大量データ取得時の制限（最大1000件等）
- **時間範囲指定**: ModSecurity照合用のaccess_time範囲指定で効率化
- **照合順序**: utf8mb4_unicode_ci対応でマルチバイト文字の正確な処理

## セキュリティ対策
- **SQLインジェクション対策**: 全パラメータのプリペアドステートメントバインド
- **データサニタイズ**: 入力値の適切な検証・変換
- **アクセス制御**: 認証済みセッションでのみアクセス可能

## 運用・設定

### 監視ポイント
- **検索性能**: 複雑なクエリのレスポンス時間監視
- **ModSecurity照合頻度**: 定期照合処理の実行状況
- **データ整合性**: Optional.empty()やnull戻り値の適切な処理

### トラブルシューティング
- **文字エンコーディング**: utf8mb4_unicode_ci照合順序の確認
- **インデックス不足**: EXPLAIN文による実行計画確認
- **大量データ**: LIMIT句による制限の適切な設定

## 注意事項
- **読み取り専用**: SELECT処理のみ、UPDATE/INSERT/DELETEは他クラスに委譲
- **新テーブル追加時**: 本クラスへの検索メソッド追加も検討
- **外部キー制約**: 関連テーブル間の整合性を考慮した検索条件
- **null安全処理**: Optional、Boolean（null許可）の適切な使用

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.5.0**: DbService統合対応
- **v2.0.0**: Connection引数方式完全廃止、DbService専用
- **v2.1.0**: **DbSession対応、ModSecurity照合機能強化、utf8mb4_unicode_ci対応**

---

## 実装参考

### Optional戻り値パターン
```java
return dbSession.executeWithResult(conn -> {
    try {
        // SQL実行
        if (rs.next()) {
            return Optional.of(new ServerInfo(...));
        }
        return Optional.empty();
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
});
```

### リスト戻り値パターン
```java
List<Map<String, Object>> results = new ArrayList<>();
while (rs.next()) {
    Map<String, Object> row = new HashMap<>();
    row.put("key", rs.getString("column"));
    results.add(row);
}
return results;
```

### DbSession連携パターン
```java
public static ReturnType methodName(DbSession dbSession, Parameters...) throws SQLException {
    return dbSession.executeWithResult(conn -> {
        try (var pstmt = conn.prepareStatement(sql)) {
            // パラメータ設定
            // 結果処理
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    });
}
```
