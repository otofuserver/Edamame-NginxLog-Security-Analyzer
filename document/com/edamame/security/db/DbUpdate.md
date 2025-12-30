# DbUpdate

対象: `src/main/java/com/edamame/security/db/DbUpdate.java`

## 概要
- UPDATE 系のデータベース操作（サーバー情報更新、エージェント統計更新、アクセスログの ModSecurity 状態更新、URL ホワイトリスト化、ロール階層管理など）を集約するユーティリティクラスの仕様。
- `DbSession` を受け取り、トランザクション制御や例外ラップを行いつつ SQL を実行する設計。

## 主な機能
- サーバー情報更新（`updateServerInfo`）
- サーバー最終ログ受信時刻更新（`updateServerLastLogReceived`）
- エージェントハートビート更新（`updateAgentHeartbeat`）
- エージェントログ統計更新（`updateAgentLogStats`）
- access_log の ModSecurity フラグ更新（`updateAccessLogModSecStatus`）
- エージェントの inactive 化（`deactivateAgent`, `deactivateAllAgents`）
- URL をホワイトリスト化する更新（`updateUrlWhitelistStatus`）
- サーバーごとのデフォルトロール階層追加（`addDefaultRoleHierarchy`）

## 挙動
- 各メソッドは `DbSession.execute` または `DbSession.executeWithResult` を用いてデータベース操作を行う。SQL 実行中に発生した `SQLException` は内部で `RuntimeException` にラップして上位に伝播する。
- 更新処理の成否は `AppLogger` で INFO/WARN/ERROR に分けてログされる。成功時には debug/info ログ、失敗時には error/warn ログを出力する。
- ロール階層の追加は既存データを読み取り JSON 配列をマージして更新する（Jackson を利用して JSON をパース/シリアライズする）。

## 細かい指定された仕様
- SQL は PreparedStatement を用いてパラメータバインドし、SQL インジェクションを防止する。
- `updateServerInfo` は `last_log_received` を現在時刻に更新し、`updated_at` を NOW() に設定する。
- `updateAgentHeartbeat` は `tcp_connection_count` をインクリメントし、`last_heartbeat` を NOW() に更新する。戻り値は更新件数（int）。
- `updateAgentLogStats` は `total_logs_received` を加算し `last_log_count` を更新する。
- `updateAccessLogModSecStatus` は access_log.id を指定して `blocked_by_modsec` を更新する。
- `updateUrlWhitelistStatus` は URL の `is_whitelisted` を true にし `updated_at` を NOW() に設定する。
- `addDefaultRoleHierarchy` の実装は以下の仕様に従う:
  - 基本ロール `admin/operator/viewer` に対して、サーバー固有ロール（`${serverName}_admin` 等）の ID を取得して `inherited_roles` JSON 配列に追加する。
  - 既に存在する場合は重複を避ける（id の重複チェック）。
  - 取得/更新中の例外はログ出力され続行される（部分失敗が起きてもプロセスは停止させない）。
- 例外とログ: 重大な SQLException 発生時は `AppLogger.error` を出し、呼び出し元でのリカバリを想定する。

## その他
- トランザクション境界が必要な複数更新がある場合は、呼び出し側が `DbSession` のトランザクション API を利用してまとめて実行すること（このクラスは個別の更新ユーティリティを提供する責務に限定）。
- データ型の取り扱いは null 安全を考慮し、必要なら呼び出し側で事前バリデーションを行うこと。
- ログメッセージに機密情報（パスワード等）を含めないこと。運用環境では Logback 等に切り替え、ログレベル運用を行うこと。

## 主なメソッド（既存）
- `public static void updateServerInfo(DbSession dbSession, String serverName, String description, String logPath)`
- `public static void updateServerLastLogReceived(DbSession dbSession, String serverName)`
- `public static int updateAgentHeartbeat(DbSession dbSession, String registrationId)`
- `public static int updateAgentLogStats(DbSession dbSession, String registrationId, int logCount)`
- `public static int updateAccessLogModSecStatus(DbSession dbSession, Long accessLogId, boolean blockedByModSec)`
- `public static int deactivateAgent(DbSession dbSession, String registrationId)`
- `public static void deactivateAllAgents(DbSession dbSession)`
- `public static int updateUrlWhitelistStatus(DbSession dbSession, String serverName, String method, String fullUrl)`
- `public static void addDefaultRoleHierarchy(DbSession dbSession, String serverName)`

## 変更履歴
- 2.1.0 - 2025-12-31: フォーマット統一（仕様書を統一フォーマットへ変換）

## コミットメッセージ例
- docs(db): DbUpdate を統一フォーマットへ変換
