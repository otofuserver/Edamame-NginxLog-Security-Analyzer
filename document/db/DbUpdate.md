# DbUpdate.java 仕様書

## 役割
- データベースのレコード更新処理を担当。
- 各テーブルのUPDATE処理・条件付き更新を実装。
- サーバー・エージェント・統計情報などのUPDATE系処理を集約。
- サーバー・エージェントの状態変更（active/inactive）や一括更新も担当。
- URLレジストリのホワイトリスト状態更新も管理。
- **ModSecurityアラート関連付けシステムのaccess_logステータス更新も対応**。

## DbService専用実装（v2.0.0～）
- **Connection引数方式は廃止**し、DbServiceインスタンスを受け取る方式に統一。
- Connection管理・例外処理・ログ出力はDbServiceが担当。
- 直接ConnectionやBiConsumer loggerを渡す方式はサポートしない。

## 主なメソッド
- `public static void updateServerInfo(DbService dbService, String serverName, String description, String logPath)`
  - serversテーブルのserver_description, log_path, last_log_received, updated_atを更新。
- `public static void updateServerLastLogReceived(DbService dbService, String serverName)`
  - serversテーブルのlast_log_receivedをNOW()で更新。
- `public static int updateAgentHeartbeat(DbService dbService, String registrationId)`
  - agent_serversテーブルのlast_heartbeat, tcp_connection_countを更新。
- `public static int updateAgentLogStats(DbService dbService, String registrationId, int logCount)`
  - agent_serversテーブルのlast_log_count, total_logs_receivedを更新。
- `public static int deactivateAgent(DbService dbService, String registrationId)`
  - agent_serversテーブルのstatusを'inactive'に変更（個別エージェントの停止）。
- `public static void deactivateAllAgents(DbService dbService)`
  - agent_serversテーブルの全activeエージェントを一括で'inactive'に変更。
- `public static int updateUrlWhitelistStatus(DbService dbService, String serverName, String method, String fullUrl)`
  - url_registryテーブルのis_whitelistedをtrueに更新（ホワイトリスト化）。
- **`public static int updateAccessLogModSecStatus(DbService dbService, Long accessLogId, boolean blockedByModSec)`**
  - **access_logテーブルのblocked_by_modsecフラグを更新（ModSecurityキューベース関連付け用）**。

## 詳細仕様

### updateAccessLogModSecStatus メソッド（v3.0.0追加）
**用途**: ModSecurityアラートとの関連付けが確認された際にaccess_logのブロック状態を更新
**呼び出し条件**: ModSecurityキューから一致するアラートが検出された時のみ
**更新内容**: 
- `blocked_by_modsec = true/false`
**戻り値**: 更新された行数（int）
**エラー処理**: SQLException発生時はRuntimeExceptionでラップし、ERRORログ出力
**関連システム**: ModSecurityキューベース関連付けシステム（AgentTcpServer.processLogEntries）

### updateUrlWhitelistStatus メソッド
**用途**: URLをホワイトリスト状態（is_whitelisted=true）に更新
**呼び出し条件**: whitelist_mode有効かつ指定IPからのアクセス時のみ利用
**更新内容**: 
- `is_whitelisted = true`
- `updated_at = NOW()`
**戻り値**: 更新された行数（int）
**エラー処理**: SQLException発生時はRuntimeExceptionでラップし、ERRORログ出力

### エージェント状態管理
**deactivateAgent**: 特定のエージェントを個別にinactive化
**deactivateAllAgents**: 全アクティブエージェントを一括inactive化（サーバー終了時）
**条件**: status='active'のレコードのみ対象

## ロジック
- 各UPDATEはプリペアドステートメントで安全に実行。
- サーバー名・エージェントIDが未登録の場合はWARNログを出力。
- 更新件数・失敗時はINFO/WARN/ERRORログで明確に記録。
- 例外時はcatchでエラーログを出力し、RuntimeExceptionで再スロー。
- ログ出力はdbService.log(message, level)でINFO/DEBUG/ERROR/WARNを明示。
- URL更新処理では、更新対象が見つからない場合も正常終了（戻り値0）。
- **ModSecurityブロック状態更新は、ModSecurityキューからの一致検出時のみ実行**。

## DbServiceでの使用方法
```java
// DbServiceから自動的に呼び出される（Connection管理不要）
try (DbService db = new DbService(url, props, logger)) {
    DbUpdate.updateServerInfo(db, serverName, description, logPath);
    int updated = DbUpdate.updateAgentHeartbeat(db, registrationId);
    DbUpdate.deactivateAllAgents(db);
    
    // ModSecurityアラート関連付け後のステータス更新（v3.0.0追加）
    int statusUpdated = DbUpdate.updateAccessLogModSecStatus(db, accessLogId, true);
}
```

## テーブル対応関係
- **servers**: updateServerInfo, updateServerLastLogReceived
- **agent_servers**: updateAgentHeartbeat, updateAgentLogStats, deactivateAgent, deactivateAllAgents
- **url_registry**: updateUrlWhitelistStatus
- **access_log**: updateAccessLogModSecStatus（v3.0.0追加）

## ModSecurityキューベースシステム連携（v3.0.0）
### 処理フロー
1. **ModSecurityアラート検出**: error.logからアラートを抽出してModSecurityQueueに追加
2. **HTTPリクエスト処理**: access.logからリクエストを解析してaccess_logテーブルに保存（blocked_by_modsec=false）
3. **関連付け実行**: ModSecurityQueueから時間・URL・サーバー名が一致するアラートを検索
4. **ステータス更新**: 一致したアラートがある場合、updateAccessLogModSecStatusでblocked_by_modsec=trueに更新
5. **アラート保存**: 一致したアラー��をmodsec_alertsテーブルに保存

### 関連付け条件
- **時間窓**: 30秒以内の一致
- **サーバー名**: 完全一致
- **URL**: 完全一致、パス一致、部分一致の複数レベル対応
- **HTTPメソッド**: GET、POST、PUT、DELETE等

## 注意事項
- **DbService経由での呼び出しのみサポート**: Connection引数を意識する必要がない。
- UPDATE対象カラム・テーブル追加時は本クラスの対応も必ず追加すること。
- 仕様変更時は本仕様書・実装・db_schema_spec.md・CHANGELOG.mdを同時更新。
- サーバー名の照合順序（utf8mb4_unicode_ci）に必ず対応すること。
- エージェントの状態管理（active/inactive）は本クラスで一元管理。
- URLホワイトリスト更新は既存URLの再評価時のみ実行（新規URL登録時は除く）。
- **ModSecurityブロック状態更新は、正確な関連付けが確認された場合のみ実行**。

## エラーハンドリング
- 例外はcatchでERRORログ出力後、RuntimeExceptionでラップして再スロー。
- DbServiceが例外処理・ログ出力を一元管理。

## セキュリティ要件
- プリペアドステートメントでSQLインジェクション対策実装済み。
- ホワイトリスト更新は厳格な条件チェック後のみ実行。
- ModSecurityブロック状態更新は信頼できる関連付けロジック経由のみ実行。
- データベース接続エラー時は適切なエラーハンドリングを実行。

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応、Connection管理の自動化
- **v2.0.0**: Connection引数方式を完全廃止、DbService専用に統一
- **v3.0.0**: ModSecurityキューベース関連付けシステム対応、updateAccessLogModSecStatusメソッド追加

---

### 参考：実装例
- updateServerInfo, updateServerLastLogReceived, updateAgentHeartbeat, updateAgentLogStats, deactivateAgent, deactivateAllAgents, updateUrlWhitelistStatus, updateAccessLogModSecStatus などをpublic staticで実装。
- ログ出力はINFO/DEBUG/ERROR/WARNで明確に記録。
- 戻り値のint型は更新行数を示し、処理結果の判定に使用。
- DbServiceパターンにより、Connection引数を意識せずに利用可能。
