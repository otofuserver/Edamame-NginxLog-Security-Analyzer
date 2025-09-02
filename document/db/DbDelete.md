# DbDelete.java 仕様書

## 概要
**バージョン**: v2.2.0  
**更新日**: 2025-09-02

## 役割
- DBログ自動削除バッチ処理ユーティリティ
- **サーバーデータ一括削除機能** ←NEW
- settingsテーブルの保存日数設定（log_retention_days）に従い、各テーブルの古いレコードを自動削除
- 1日1回の定期実行を想定した設計

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド

### `public static void runLogCleanupBatch(DbSession dbSession)`
- **機能**: settingsテーブルのlog_retention_days設定に基づき、古いレコードを一括削除
- **対象テーブル**:
  - `modsec_alerts` (外部キー制約のため最初に削除)
  - `access_log`
  - `agent_servers` (status='inactive'かつ古いlast_heartbeatのみ)
  - `login_history`
  - `action_execution_log`
- **実行順序**: 外部キー制約を考慮し、modsec_alerts → access_log → その他の順で削除
- **ログ出力**: AppLogger.info/errorを使用してバッチ結果を記録

### `public static void deleteServerData(DbSession dbSession, String serverName)` ←NEW
- **機能**: 指定サーバーに関連するデータを一括削除
- **対象テーブル**:
  1. `modsec_alerts` (外部キー制約のため最初)
  2. `access_log` 
  3. `url_registry`
  4. `users_roles` (roles削除前に実行)
  5. `roles` (他のロールのinherited_rolesからも削除)
  6. `servers` (最後に実行)
- **トランザクション管理**: 全削除処理を1つのトランザクションで実行
- **ロールバック**: エラー時は自動ロールバック
- **ログ出力**: 各段階での削除件数とエラーログを記録

## プライベートメソッド詳細

### 既存メソッド（ログクリーンアップ用）

#### `deleteOldModSecAlerts(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM modsec_alerts
WHERE access_log_id IN (
    SELECT id FROM access_log
    WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
)
```
- access_logとの関連性を考慮してサブクエリで削除
- 外部キー制約対応のため最初に実行

#### `deleteOldAccessLogs(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM access_log WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- access_time列を基準とした単純な日付条件削除

#### `deleteOldAgentServers(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM agent_servers
WHERE status = 'inactive'
AND last_heartbeat < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- **重要**: status='inactive'のレコードのみ削除
- アクティブなエージェントサーバーは保存日数に関係なく保持

#### `deleteOldLoginHistory(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- login_time列を基準とした単純な日付条件削除

#### `deleteOldActionExecutionLog(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM action_execution_log WHERE execution_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- execution_time列を基準とした単純な日付条件削除

### 新規メソッド（サーバーデータ削除用）←NEW

#### `deleteModSecAlertsByServer(DbSession dbSession, String serverName)`
```sql
DELETE FROM modsec_alerts
WHERE access_log_id IN (
    SELECT id FROM access_log
    WHERE server_name = ?
)
```
- 指定サーバーのModSecurityアラートを削除
- access_logとの関連を考慮したサブクエリ使用

#### `deleteAccessLogsByServer(DbSession dbSession, String serverName)`
```sql
DELETE FROM access_log WHERE server_name = ?
```
- 指定サーバーのアクセスログを削除
- server_name列での絞り込み

#### `deleteUrlRegistryByServer(DbSession dbSession, String serverName)`
```sql
DELETE FROM url_registry WHERE server_name = ?
```
- 指定サーバーのURL登録情報を削除
- server_name列での絞り込み

#### `deleteUsersRolesByServer(DbSession dbSession, String serverName)`
```sql
DELETE FROM users_roles
WHERE role_id IN (
    SELECT id FROM roles
    WHERE role_name LIKE CONCAT(?, '_%')
)
```
- 指定サーバーに関連するユーザーロール関連付けを削除
- `serverName_*`パターンのロールIDを検索してusers_rolesから削除

#### `deleteRolesByServer(DbSession dbSession, String serverName)`
```sql
-- 削除対象のrole_idを取得
SELECT id FROM roles WHERE role_name LIKE CONCAT(?, '_%')

-- inherited_rolesから該当role_idを削除
UPDATE roles
SET inherited_roles = JSON_REMOVE(inherited_roles,
    JSON_UNQUOTE(JSON_SEARCH(inherited_roles, 'one', ?)))
WHERE JSON_SEARCH(inherited_roles, 'one', ?) IS NOT NULL

-- rolesテーブルから削除
DELETE FROM roles WHERE role_name LIKE CONCAT(?, '_%')
```
- **特殊処理**: 他のロールの`inherited_roles`（JSON配列）からも該当role_idを削除
- 3段階の処理：ID取得 → inherited_roles更新 → レコード削除

#### `deleteServerByName(DbSession dbSession, String serverName)`
```sql
DELETE FROM servers WHERE server_name = ?
```
- 指定サーバー情報を削除
- 削除対象が見つからない場合は警告ログ出力

## 削除ロジック詳細

### ログクリーンアップ（既存機能）

#### 1. 設定値取得
```sql
SELECT log_retention_days FROM settings WHERE id = 1
```
- settingsテーブルから統一的な保存日数を取得
- 設定が存在しない場合は警告ログを出力してスキップ

#### 2. 削除実行条件
- `retentionDays >= 0` の場合のみ削除処理を実行
- 負の値の場合は削除をスキップ（無制限保存）

#### 3. 削除順序（外部キー制約対応）
1. `modsec_alerts` (access_logへの外部キ��制約のため最初)
2. `access_log`
3. `agent_servers` (非アクティブのみ)
4. `login_history`
5. `action_execution_log`

### サーバーデータ削除（新機能）←NEW

#### 1. トランザクション開始
- `dbSession.executeInTransaction()`で全削除処理を包括
- エラー時は自動ロールバック

#### 2. 削除順序（外部キー制約対応）
1. **modsec_alerts** → ModSecurityアラート（access_logへの参照のため最初）
2. **access_log** → アクセスログ
3. **url_registry** → URL登録情報
4. **users_roles** → ユーザーロール関連付け（roles削除前）
5. **roles** → ロール情報（inherited_rolesからも削除）
6. **servers** → サーバー情報（最後）

#### 3. ロール削除の特殊処理
- サーバー名に一致するロール（`serverName_*`）を検索
- 他のロールの`inherited_roles`（JSON配列）から該当role_idを削除
- JSON操作にはMySQL 8.xのJSON関数を使用

#### 4. エラーハンドリング
- 各段階でのSQL例外を個別にキャッチ
- エラー時はロールバックして処理中断
- 詳細なログ出力で問題特定を支援

## DbSessionでの使用方法

### ログクリーンアップ（既存）
```java
try {
    dbSession.execute(conn -> {
        DbDelete.runLogCleanupBatch(dbSession);
    });
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

### サーバーデータ削除（新規）←NEW
```java
try {
    // 単純な削除
    DbDelete.deleteServerData(dbSession, "web-server-01");
    
    // DbService経由での削除
    DbService.deleteServerData("web-server-01");
    
} catch (Exception e) {
    AppLogger.error("サーバーデータ削除失敗: " + e.getMessage());
    // エラー時は自動ロールバック済み
}
```

## エラーハンドリング

### 例外処理階層
1. **各プライベートメソッド**: SQLException → RuntimeExceptionでラップ
2. **runLogCleanupBatch/deleteServerData**: 全体的なエラーをキャッチして致命的エラーとしてログ出力
3. **DbSession**: Connection管理・トランザクション管理を自動化

### ログ出力レベル
- **INFO**: 正常削除完了時の削除件数
- **WARN**: settings設定が見つからない場合、削除対象サーバーが見つからない場合
- **ERROR**: SQL実行エラー・その他の例外

## セキュリティ対策
- **SQLインジェクション対策**: すべてのクエリでPreparedStatementを使用
- **パラメータバインド**: すべてのパラメータをプリペアドステートメントでバインド
- **トランザクション整合性**: 部分削除を防ぐ適切なトランザクション管理

## パフォーマンス考慮事項
- **大量データ対応**: バッチサイズの制限なし（MySQLの設定に依存）
- **インデックス活用**: 各テーブルの時刻系・server_name系カラムにインデックスが必要
- **外部キー制約**: 削除順序を最適化してデッドロック回避
- **JSON操作**: MySQL 8.xのJSON関数による効率的なinherited_roles更新

## 設定・運用

### ログクリーンアップ推奨設定値
- **開発環境**: 7-30日
- **本番環境**: 90-365日
- **法的要件**: 組織のデータ保持ポリシーに従う

### サーバーデータ削除運用←NEW
- **用途**: サーバー廃止・設定リセット時の一括削除
- **実行前確認**: 削除対象データの確認（バックアップ推奨）
- **影響範囲**: 指定サーバーに関連するすべてのデータが削除される
- **復旧不可**: 削除後の復旧は不可（事前バックアップ必須）

### 定期実行
- **ログクリーンアップ**: 1日1回（深夜時間帯を推奨）
- **サーバーデータ削除**: 手動実行のみ（定期実行非推奨）
- **実行タイミング**: システム負荷の低い時間帯
- **監視**: ログ出力を監視してバッチ成功/失敗を確認

## 注意事項
- **新テーブル追加時**: 本クラスの対応も必須
- **外部キー制約**: 削除順序の変更時は制約を確認
- **大量削除**: 本番環境での初回実行時はデータ量を事前確認
- **バックアップ**: 重要データの削除前はバックアップを推奨
- **サーバー削除の取り消し不可**: deleteServerDataは復旧不可のため慎重に実行

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応
- **v2.0.0**: Connection引数方式完全廃止、DbService専用
- **v2.1.0**: DbSession対応、settingsテーブル統合、外部キー制約対応
- **v2.2.0**: **サーバーデータ一括削除機能追加、ロールのinherited_roles対応**

---

## 実装参考

### エラーハンドリングパターン
```java
try {
    // SQL実行
    int deleted = pstmt.executeUpdate();
    if (deleted > 0) {
        AppLogger.info("削除完了: " + deleted + " 件");
    }
} catch (SQLException e) {
    AppLogger.error("削除エラー: " + e.getMessage());
    throw new RuntimeException(e);
}
```

### DbSession連携パターン
```java
dbSession.execute(conn -> {
    try (var pstmt = conn.prepareStatement(sql)) {
        pstmt.setInt(1, retentionDays);
        // 実行処理
    }
});
```

### サーバーデータ削除パターン←NEW
```java
// 安全なサーバーデータ削除
public void deleteServerSafely(String serverName) {
    try {
        // 削除前確認
        if (DbService.existsServerByName(serverName)) {
            AppLogger.info("サーバーデータ削除開始: " + serverName);
            
            // 一括削除実行
            DbService.deleteServerData(serverName);
            
            AppLogger.info("サーバーデータ削除完了: " + serverName);
        } else {
            AppLogger.warn("削除対象サーバーが存在しません: " + serverName);
        }
    } catch (Exception e) {
        AppLogger.error("サーバーデータ削除に失敗: " + serverName + " - " + e.getMessage());
    }
}
```
