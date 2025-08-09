# DbUpdate.java 仕様書

## 役割
- データベースのレコード更新処理を担当。
- 各テーブルのUPDATE処理・条件付き更新を実装。
- サーバー・エージェント・統計情報などのUPDATE系処理を集約。
- サーバー・エージェントの状態変更（active/inactive）や一括更新も担当。

## 主なメソッド
- `public static void updateServerInfo(Connection conn, String serverName, String description, String logPath, BiConsumer<String, String> logFunc)`
  - serversテーブルのserver_description, log_path, last_log_received, updated_atを更新。
- `public static void updateServerLastLogReceived(Connection conn, String serverName, BiConsumer<String, String> logFunc)`
  - serversテーブルのlast_log_receivedをNOW()で更新。
- `public static int updateAgentHeartbeat(Connection conn, String registrationId, BiConsumer<String, String> logFunc)`
  - agent_serversテーブルのlast_heartbeat, tcp_connection_countを更新。
- `public static int updateAgentLogStats(Connection conn, String registrationId, int logCount, BiConsumer<String, String> logFunc)`
  - agent_serversテーブルのlast_log_count, total_logs_receivedを更新。
- `public static int deactivateAgent(Connection conn, String registrationId, BiConsumer<String, String> logFunc)`
  - agent_serversテーブルのstatusを'inactive'に変更（個別エージェントの停止）。
- `public static void deactivateAllAgents(Connection conn, BiConsumer<String, String> logFunc)`
  - agent_serversテーブルの全activeエージェントを一括で'inactive'に変更。

## ロジック
- 各UPDATEはプリペアドステートメントで安全に実行。
- サーバー名・エージェントIDが未登録の場合はWARNログを出力。
- 更新件数・失敗時はINFO/WARN/ERRORログで明確に記録。
- 例外時はcatchでエラーログを出力。
- ログ出力はBiConsumer<String, String> logFuncでINFO/DEBUG/ERROR/WARNを明示。

## 注意事項
- UPDATE対象カラム・テーブル追加時は本クラスの対応も必ず追加すること。
- 仕様変更時は本仕様書・実装・db_schema_spec.md・CHANGELOG.mdを同時更新。
- サーバー名の照合順序（utf8mb4_unicode_ci）に必ず対応すること。
- エージェントの状態管理（active/inactive）は本クラスで一元管理。

---

### 参考：実装例
- updateServerInfo, updateServerLastLogReceived, updateAgentHeartbeat, updateAgentLogStats, deactivateAgent, deactivateAllAgents などをpublic staticで実装。
- ログ出力はINFO/DEBUG/ERROR/WARNで明確に記録。