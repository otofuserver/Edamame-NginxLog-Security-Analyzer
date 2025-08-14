# DbDelete.java 仕様書

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- DBログ自動削除バッチ処理ユーティリティ
- settingsテーブルの保存日数設定（log_retention_days）に従い、各テーブルの古いレコードを自動削除
- 1日1回の定期実行を想定した設計

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力��DbSessionが担当

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

## プライベートメソッド詳細

### `deleteOldModSecAlerts(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM modsec_alerts
WHERE access_log_id IN (
    SELECT id FROM access_log
    WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
)
```
- access_logとの関連性を考慮してサブクエリで削除
- 外部キー制約対応のため最初に実行

### `deleteOldAccessLogs(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM access_log WHERE access_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- access_time列を基準とした単純な日付条件削除

### `deleteOldAgentServers(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM agent_servers
WHERE status = 'inactive'
AND last_heartbeat < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- **重要**: status='inactive'のレコードのみ削除
- アクティブなエージェントサーバーは保存日数に関係なく保持

### `deleteOldLoginHistory(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- login_time列を基準とした単純な日付条件削除

### `deleteOldActionExecutionLog(DbSession dbSession, int retentionDays)`
```sql
DELETE FROM action_execution_log WHERE execution_time < DATE_SUB(NOW(), INTERVAL ? DAY)
```
- execution_time列を基準とした単純な日付条件削除

## 削除ロジック詳細

### 1. 設定値取得
```sql
SELECT log_retention_days FROM settings WHERE id = 1
```
- settingsテーブルから統一的な保存日数を取得
- 設定が存在しない場合は警告ログを出力してスキップ

### 2. 削除実行条件
- `retentionDays >= 0` の場合のみ削除処理を実行
- 負の値の場合は削除をスキップ（無制限保存）

### 3. 削除順序（外部キー制約対応）
1. `modsec_alerts` (access_logへの外部キー制約のため最初)
2. `access_log`
3. `agent_servers` (非アクティブのみ)
4. `login_history`
5. `action_execution_log`

## DbSessionでの使用方法
```java
// 標準的な使用パターン
try {
    dbSession.execute(conn -> {
        DbDelete.runLogCleanupBatch(dbSession);
    });
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

## エラーハンドリング

### 例外処理階層
1. **各プライベートメソッド**: SQLException → RuntimeExceptionでラップ
2. **runLogCleanupBatch**: 全体的なエラーをキャッチして致命的エラーとしてログ出力
3. **DbSession**: Connection管理・トランザクション管理を自動化

### ログ出力レベル
- **INFO**: 正常削除完了時の削除件数
- **WARN**: settings設定が見つからない場合
- **ERROR**: SQL実行エラー・その他の例外

## セキュリティ対策
- **SQLインジェクション対策**: すべてのクエリでPreparedStatementを使用
- **パラメータバインド**: retentionDaysは必ずプリペアドステートメントでバインド

## パフォーマンス考慮事項
- **大量データ対応**: バッチサイズの制限なし（MySQLの設定に依存）
- **インデックス活用**: 各テーブルの時刻系カラムにインデックスが必要
- **外部キー制約**: 削除順序を最適化してデッドロック回避

## 設定・運用

### 推奨設定値
- **開発環境**: 7-30日
- **本番環境**: 90-365日
- **法的要件**: 組織のデータ保持ポリシーに従う

### 定期実行
- **実行頻度**: 1日1回（深夜時間帯を推奨）
- **実行タイミング**: システム負荷の低い時間帯
- **監視**: ログ出力を監視してバッチ成功/失敗を確認

## 注意事項
- **新テーブル追加時**: 本クラスの対応も必須
- **外部キー制約**: 削除順序の変更時は制約を確認
- **大量削除**: 本番環境での初回実行時はデータ量を事前確認
- **バックアップ**: 重要データの削除前はバックアップを推奨

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応
- **v2.0.0**: Connection引数方式完全廃止、DbService専用
- **v2.1.0**: **DbSession対応、settingsテーブル統合、外部キー制約対応**

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
