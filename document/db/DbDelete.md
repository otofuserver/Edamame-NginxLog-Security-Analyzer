# DbDelete.java 仕様書

## 役割
- DBログ自動削除バッチ処理ユーティリティ。
- settingsテーブルの保存日数に従い、access_log・modsec_alerts・login_history・action_execution_log・agent_servers等の古いレコードを自動削除する。
- 1日1回の定期実行を想定。

## DbService専用実装（v2.0.0～）
- **Connection引数方式は廃止**し、DbServiceインスタンスを受け取る方式に統一。
- Connection管理・例外処理・ログ出力はDbServiceが担当。
- 直接ConnectionやBiConsumer loggerを渡す方式はサポートしない。

## 主なメソッド
- `public static void runLogCleanupBatch(DbService dbService)`
  - 各種保存日数設定に従い、古いレコードを一括削除するバッチ処理。
  - access_log, modsec_alerts, login_history, action_execution_log, agent_serversテーブルに対応。
  - agent_serversはaccess_log_retention_daysで指定された日数より古いlast_heartbeatかつstatus != 'active'のレコードを削除。
  - ログ出力はdbService.log(message, level)でINFO/ERRORレベルを指定。

## プライベートメソッド
- `private static void deleteOldModSecAlerts(DbService dbService, int retentionDays)`
  - 古いModSecurityアラートを削除。detected_atまたは関連するaccess_logが古い場合に削除。
- `private static void deleteOldAccessLogs(DbService dbService, int retentionDays)`
  - 古いアクセスログを削除。access_timeが保存日数を超えた場合に削除。
- `private static void deleteOldAgentServers(DbService dbService, int retentionDays)`
  - 古いエージェントサーバー記録を削除。status != 'active'かつlast_heartbeatが古いレコードのみ削除。
- `private static void deleteOldLoginHistory(DbService dbService, int retentionDays)`
  - 古いログイン履歴を削除。login_timeが保存日数を超えた場合に削除。
- `private static void deleteOldActionExecutionLog(DbService dbService, int retentionDays)`
  - 古いアクション実行ログを削除。execution_timeが保存日数を超えた場合に削除。

## ロジック
- settingsテーブルから各種保存日数（access_log_retention_days等）を取得。
- 各保存日数が0以上の場合のみ、該当テーブルの古いレコードを削除。
- modsec_alertsはaccess_logより先に削除（外部キー制約対応）。
- agent_serversはaccess_log_retention_daysで指定された日数より古いlast_heartbeatかつstatus != 'active'のレコードを削除。
- SQL例外発生時はERRORログを出力。
- トランザクション管理はDbServiceで実施（本メソッド内では自動コミット）。

## DbServiceでの使用方法
```java
// DbServiceから自動的に呼び出される
try (DbService db = new DbService(url, props, logger)) {
    // Connection管理・例外処理はDbServiceが担当
    DbDelete.runLogCleanupBatch(db);
}
```

## 注意事項
- 保存日数が-1またはNULLの場合は削除処理をスキップ。
- SQLインジェクション対策としてプリペアドステートメントを使用。
- テーブル追加時は本メソッドの対応も必ず追加すること。
- **DbServiceパターンへの統合により、呼び出し元でのConnection管理が不要**。

## エラーハンドリング
- 例外はcatchでERRORログ出力後、RuntimeExceptionでラップして再スロー。
- DbServiceが例外処理・ログ出力を一元管理。

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応、Connection管理の自動化
- **v2.0.0**: Connection引数方式を完全廃止、DbService専用に統一

---

### 参考：実装例
- runLogCleanupBatchはpublic staticメソッドとして実装。
- 例外時はcatchでERRORログを出力。
- 削除件数はINFOログで出力。
- DbServiceパターンにより、Connection引数を意識せずに利用可能。
