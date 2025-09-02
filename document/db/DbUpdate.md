# DbUpdate.java 仕様書

## 概要
**バージョン**: v2.1.1  # addDefaultRoleHierarchy仕様・履歴更新  
**更新日**: 2025-08-18

## 役割
- データベースのレコード更新処理専用クラス
- 各テーブルのUPDATE処理・条件付き更新を実装
- サーバー・エージェント・統計情報などのUPDATE系処理を集約
- **INSERT系処理はDbRegistry.javaに完全移譲**

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド詳細

### `updateServerInfo(DbSession dbSession, String serverName, String description, String logPath)`
- **機能**: サーバー情報の包括的更新
- **更新項目**: server_description、log_path、last_log_received、updated_at
- **照合順序**: utf8mb4_unicode_ci対応
- **null安全処理**: description、logPath のnull値を空文字に変換

```sql
UPDATE servers
SET server_description = ?,
    log_path = ?,
    last_log_received = NOW(),
    updated_at = NOW()
WHERE server_name = ? COLLATE utf8mb4_unicode_ci
```

### `updateServerLastLogReceived(DbSession dbSession, String serverName)`
- **機能**: サーバーの最終ログ受信時刻のみ更新
- **用途**: 定期的なログ監視処理での軽量更新
- **警告処理**: 更新対象が見つからない場合はWARNログ出力

```sql
UPDATE servers
SET last_log_received = NOW()
WHERE server_name = ? COLLATE utf8mb4_unicode_ci
```

### `updateAgentHeartbeat(DbSession dbSession, String registrationId)`
- **機能**: エージェントのハートビート更新
- **戻り値**: 更新された行数（int）
- **更新項目**: last_heartbeat、tcp_connection_count（インクリメント）
- **用途**: エージェント生存確認・接続統計管理

```sql
UPDATE agent_servers 
SET last_heartbeat = NOW(), 
    tcp_connection_count = tcp_connection_count + 1 
WHERE registration_id = ?
```

### `updateAgentLogStats(DbSession dbSession, String registrationId, int logCount)`
- **機能**: エージェントのログ処理統計更新
- **戻り値**: 更新された行数（int）
- **更新項目**: total_logs_received（累積加算）、last_log_count（最新値）
- **用途**: ログ処理量監視・統計情報管理

```sql
UPDATE agent_servers 
SET total_logs_received = total_logs_received + ?, 
    last_log_count = ? 
WHERE registration_id = ?
```

### `updateAccessLogModSecStatus(DbSession dbSession, Long accessLogId, boolean blockedByModSec)`
- **機能**: access_logのModSecurityブロック状態更新
- **戻り値**: 更新された行数（int）
- **用途**: ModSecurityアラートとの関連付け後のス��ータス反映
- **重要**: ModSecurityキューベース関連付けシステムの核心機能

```sql
UPDATE access_log 
SET blocked_by_modsec = ? 
WHERE id = ?
```

### `deactivateAgent(DbSession dbSession, String registrationId)`
- **機能**: 特定エージェントの個別非アクティブ化
- **戻り値**: 更新された行数（int）
- **用途**: 個別エージェントの手動停止・障害対応

```sql
UPDATE agent_servers 
SET status = 'inactive' 
WHERE registration_id = ?
```

### `deactivateAllAgents(DbSession dbSession)`
- **機能**: 全アクティブエージェントの一括非アクティブ化
- **対象**: status='active'のレコードのみ
- **用途**: サーバー終了時・全体リセット時

```sql
UPDATE agent_servers 
SET status = 'inactive' 
WHERE status = 'active'
```

### `updateUrlWhitelistStatus(DbSession dbSession, String serverName, String method, String fullUrl)`
- **機能**: URLのホワイトリスト状態更新
- **戻り値**: 更新された行数（int）
- **更新項目**: is_whitelisted（true固定）、updated_at
- **用途**: ホワイトリスト機能でのURL安全化処理

```sql
UPDATE url_registry
SET is_whitelisted = true, updated_at = NOW()
WHERE server_name = ? AND method = ? AND full_url = ?
```

### `addDefaultRoleHierarchy(DbSession dbSession, String serverName)`
- **機能**: rolesテーブルのinherited_roles(JSON配列)にサーバー名ごとの下位ロールIDを追加登録
- **引数**: dbSession: データベースセッション, serverName: サーバー名
- **ロジック**:
  1. baseRoles（admin/operator/viewer）ごとに、serverName付き下位ロール（serverName_admin等）のIDを取得
  2. inherited_roles（JSON配列）に下位ロールIDを追加（JacksonでJSON処理）
  3. 既存の場合は重複追加しない
  4. ログ出力・例外処理あり
- **注意事項**: rolesテーブルのrole_nameがadmin/operator/viewerのレコードのみ対象。JSON配列の型安全な処理を行うこと。
- **例外処理**: SQLException → RuntimeExceptionでラップ、Jackson例外もcatchしてログ出力

## エラーハンドリング

### 例外処理階層
1. **各メソッド**: SQLException → RuntimeExceptionでラップ
2. **DbSession**: Connection管理・トランザクション管理を自動化
3. **ログ出力**: AppLogger.info/warn/error/debugによる詳細記録

### ログ出力レベル
- **INFO**: 正常更新完了時（件数も記録）
- **DEBUG**: 詳細なデバッグ情報（ID、更新値等）
- **WARN**: 更新対象が見つからない場合
- **ERROR**: SQL実行エラー・致命的例外

### 戻り値パターン
- **更新件数返却**: int戻り値（updateAgentHeartbeat、updateAgentLogStats等）
- **void**: 更新のみ（updateServerInfo、deactivateAllAgents等）
- **0件更新**: 正常終了（エラーではない）

## DbSessionでの使用方法
```java
// 標準的な使用パターン
try {
    // サーバー情報更新
    DbUpdate.updateServerInfo(dbSession, "web-server-01", "本番Webサーバー", "/var/log/nginx/");
    
    // 最終ログ受信時刻のみ更新
    DbUpdate.updateServerLastLogReceived(dbSession, "web-server-01");
    
    // エージェントハートビート更新
    int heartbeatUpdated = DbUpdate.updateAgentHeartbeat(dbSession, "agent-123");
    
    // ログ処理統計更新
    int statsUpdated = DbUpdate.updateAgentLogStats(dbSession, "agent-123", 50);
    
    // ModSecurityブロック状態更新
    int statusUpdated = DbUpdate.updateAccessLogModSecStatus(dbSession, 12345L, true);
    
    // エージェント非アクティブ化
    int deactivated = DbUpdate.deactivateAgent(dbSession, "agent-123");
    
    // 全エージェント非アクティブ化
    DbUpdate.deactivateAllAgents(dbSession);
    
    // URLホワイトリスト化
    int whitelistUpdated = DbUpdate.updateUrlWhitelistStatus(
        dbSession, "web-server-01", "GET", "/api/users");
        
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

## ModSecurityキューベース関連付けシステム連携

### updateAccessLogModSecStatusの重要性
このメソッドは、ModSecurityアラートとアクセスログの関連付けシステムの核心機能です。

#### 処理フロー
1. **初期保存**: アクセスログをblocked_by_modsec=falseで保存（DbRegistry）
2. **アラート照合**: ModSecurityキューから時間・URL・サーバー名が一致するアラートを検索
3. **ステータス更新**: 一致したアラートがある場合、本メソッドでblocked_by_modsec=trueに更新
4. **アラート保存**: 一致したアラートをmodsec_alertsテーブルに保存（DbRegistry）

#### 使用例（AgentTcpServerでの定期照合処理）
```java
public void performPeriodicAlertMatching() {
    try {
        // 最近のアクセスログを取得
        List<Map<String, Object>> recentLogs = 
            DbSelect.selectRecentAccessLogsForModSecMatching(dbSession, 5);
        
        for (Map<String, Object> log : recentLogs) {
            Long accessLogId = (Long) log.get("id");
            
            // ModSecurityキューから一致するアラートを検索
            List<ModSecAlert> matchingAlerts = 
                modSecQueue.findMatchingAlerts(/* 条件 */);
            
            if (!matchingAlerts.isEmpty()) {
                // ブロック状態を更新
                int updated = DbUpdate.updateAccessLogModSecStatus(
                    dbSession, accessLogId, true);
                
                if (updated > 0) {
                    AppLogger.info("ModSecurityブロック状態更新: ID=" + accessLogId);
                }
            }
        }
    } catch (SQLException e) {
        AppLogger.error("ModSecurity照合処理エラー: " + e.getMessage());
    }
}
```

## テーブル対応関係
- **servers**: updateServerInfo、updateServerLastLogReceived
- **agent_servers**: updateAgentHeartbeat、updateAgentLogStats、deactivateAgent、deactivateAllAgents
- **access_log**: updateAccessLogModSecStatus
- **url_registry**: updateUrlWhitelistStatus

## パフォーマンス考慮事項
- **インデックス活用**: WHERE句の条件項目にインデックス必須
- **バッチ更新**: deactivateAllAgentsによる効率的な一括処理
- **軽量更新**: updateServerLastLogReceivedによる最小限の更新
- **統計計算**: agent_serversのカウ��タ更新による高速集計

## セキュリティ対策
- **SQLインジェクション対策**: 全パラメータのプリペアドステートメントバインド
- **データ整合性**: 適切な制約チェックと外部キー制約の維持
- **権限制御**: 認証済みセッションでのみ更新処���を許可

## 運用・設定

### 監視ポイント
- **更新成功率**: 各メソッドの戻り値による更新件数監視
- **エージェント状態**: deactivateAgent/deactivateAllAgentsの実行状況
- **ModSecurity照合**: updateAccessLogModSecStatusの実行頻度

### トラブルシューティング
- **0件更新**: 対象レコードの存在確認（registration_id、server_name等）
- **外部キー制約**: 関連テーブルとの整合性確認
- **文字エンコーディング**: utf8mb4_unicode_ci照合順序の確認

## 注意事項
- **更新専用**: UPDATE処理のみ、INSERT/DELETE/SELECTは他クラスに委譲
- **新テーブル追加時**: 本クラスへの更新メソッド追加も検討
- **外部キー制約**: 更新時の関連テーブル間の整合性維持
- **トランザクション**: 複数テーブル更新時の一貫性保証

## バージョン履歴
- **v2.1.1**: addDefaultRoleHierarchy仕様追加

---

## 実装参考

### 戻り値ありのUPDATEパターン
```java
public static int updateMethodName(DbSession dbSession, Parameters...) throws SQLException {
    return dbSession.executeWithResult(conn -> {
        try (var pstmt = conn.prepareStatement(sql)) {
            // パラメータ設定
            int updated = pstmt.executeUpdate();
            if (updated > 0) {
                AppLogger.info("更新成功: " + updated + " 件");
            } else {
                AppLogger.warn("更新対象が見つかりません");
            }
            return updated;
        } catch (SQLException e) {
            AppLogger.error("更新エラー: " + e.getMessage());
            throw new RuntimeException(e);
        }
    });
}
```

### 戻り値なしのUPDATEパターン
```java
public static void updateMethodName(DbSession dbSession, Parameters...) throws SQLException {
    dbSession.execute(conn -> {
        try (var pstmt = conn.prepareStatement(sql)) {
            // パラメータ設定
            int updated = pstmt.executeUpdate();
            AppLogger.info("更新完了: " + updated + " 件");
        } catch (SQLException e) {
            AppLogger.error("更新エラー: " + e.getMessage());
            throw new RuntimeException(e);
        }
    });
}
```

### DbSession連携パターン
```java
dbSession.execute(conn -> {
    try {
        String sql = "UPDATE table_name SET column = ? WHERE condition = ?";
        try (var pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, value);
            pstmt.setString(2, condition);
            int updated = pstmt.executeUpdate();
            // ログ処理
        }
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
});
```
