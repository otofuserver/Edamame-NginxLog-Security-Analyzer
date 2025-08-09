# DbDelete.java 仕様書

## 役割
- DBログ自動削除バッチ処理ユーティリティ。
- settingsテーブルの保存日数に従い、access_log・modsec_alerts・login_history・action_execution_log・agent_servers等の古いレコードを自動削除する。
- 1日1回の定期実行を想定。

## 主なメソッド
- `public static void runLogCleanupBatch(Connection conn, BiConsumer<String, String> log)`
  - 各種保存日数設定に従い、古いレコードを一括削除するバッチ処理。
  - access_log, modsec_alerts, login_history, action_execution_log, agent_serversテーブルに対応。
  - agent_serversはaccess_log_retention_daysで指定された日数より古いlast_heartbeatかつstatus != 'active'のレコードを削除。
  - ログ出力はBiConsumer<String, String> logでINFO/ERRORレベルを指定。

## ロジック
- settingsテーブルから各種保存日数（access_log_retention_days等）を取得。
- 各保存日数が0以上の場合のみ、該当テーブルの古いレコードを削除。
- modsec_alertsはaccess_logより先に削除。
- agent_serversはaccess_log_retention_daysで指定された日数より古いlast_heartbeatかつstatus != 'active'のレコードを削除。
- SQL例外発生時はERRORログを出力。
- トランザクション管理は呼び出し元で実施（本メソッド内では自動コミット）。

## 注意事項
- 保存日数が-1またはNULLの場合は削除処理をスキップ。
- SQLインジェクション対策としてプリペアドステートメントを使用。
- テーブル追加時は本メソッドの対応も必ず追加すること。

---

### 参考：実装例
- runLogCleanupBatchはpublic staticメソッドとして実装。
- 例外時はcatchでERRORログを出力。
- 削除件数はINFOログで出力。