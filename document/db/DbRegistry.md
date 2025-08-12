# DbRegistry.java 仕様書

## 役割
- サーバー情報（serversテーブル）の登録・新規追加・存在確認・自動登録を担当。
- サーバーの有効/無効切り替えや説明文の更新（更新処理はDbUpdateに移譲）。
- agent_serversテーブルのエージェント登録・状態管理（ON DUPLICATE KEY UPDATEによる再登録も担当）。
- access_logテーブルのログ登録（insertAccessLog）。
- url_registryテーブルの新規URL登録（registerUrlRegistryEntry）。
- modsec_alertsテーブルのModSecurityアラート登録（insertModSecAlert）。
- ※一部のUPDATE系処理（サーバー情報・エージェント状態・統計更新など）はDbUpdate.javaに移譲。

## 主なメソッド
- `public static void registerOrUpdateServer(DbService dbService, String serverName, String description, String logPath)`
  - サーバー名で存在確認し、未登録ならINSERT、既存ならDbUpdate.updateServerInfo()でUPDATE。
  - サーバー名の照合順序（utf8mb4_unicode_ci）に対応。
- `public static String registerOrUpdateAgent(DbService dbService, Map<String, Object> serverInfo)`
  - agent_serversテーブルにエージェント情報を登録または更新。
  - ON DUPLICATE KEY UPDATEで再登録時も情報更新。
  - 登録IDは「agent-{timestamp}-{random}」形式で自動生成。
- `public static Long insertAccessLog(DbService dbService, Map<String, Object> parsedLog)`
  - access_logテーブルにログを登録し、登録IDを返す。失敗時はnull。
- `public static boolean registerUrlRegistryEntry(DbService dbService, String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType)`
  - url_registryテーブルに新規URLを登録。攻撃タイプ・ホワイトリスト判定・ログ出力対応。成功時true、失敗時false。
- `public static void insertModSecAlert(DbService dbService, Long accessLogId, Map<String, Object> modSecInfo)`
  - modsec_alertsテーブルにModSecurityアラートを登録。rule_id, severity, message, data_value, server_name等を保存。

## 移譲されたUPDATE系メソッド（DbUpdate.javaへ移動）
- `public static void updateServerInfo(...)` → DbUpdate.java
- `public static void updateServerLastLogReceived(...)` → DbUpdate.java
- `public static int updateAgentHeartbeat(...)` → DbUpdate.java
- `public static int updateAgentLogStats(...)` → DbUpdate.java
- `public static int deactivateAgent(...)` → DbUpdate.java
- `public static void deactivateAllAgents(...)` → DbUpdate.java

## ロジック
- サーバー名・エージェントIDはNULL/空文字時にデフォルト値やスキップ処理。
- SQL例外時はERROR/WARNログを出力。
- agent_serversの登録・更新はJacksonでnginx_log_pathsをJSON化。
- ON DUPLICATE KEY UPDATEでエージェント情報を再登録時も上書き。
- access_log, url_registry, modsec_alertsの登録時もINFO/ERRORログを出力。
- UPDATE系処理はDbUpdateクラスに委譲。
- ログ出力はdbService.log(message, level)でINFO/DEBUG/ERROR/WARNを明示。

## 注意事項
- サーバー名の照合順序（utf8mb4_unicode_ci）に必ず対応すること。
- agent_serversの登録IDはgenerateAgentRegistrationId()で一意生成。
- テーブル・カラム追加時は本クラスの対応も必ず追加すること。
- 例外時はcatchでエラーログを出力し、必要に応じてリトライ。
- UPDATE系のDB処理はDbUpdate.javaに集約。
- **DbService経由での呼び出しのみサポート**: Connection/BiConsumer引数方式は廃止。

---

### 参考：実装例
- registerOrUpdateServer, registerOrUpdateAgent, insertAccessLog, registerUrlRegistryEntry, insertModSecAlert などをpublic staticで実装。
- agent_serversのON DUPLICATE KEY UPDATEで再登録時も情報更新。
- access_log, url_registry, modsec_alertsの登録時もINFO/ERRORログを出力。
- UPDATE系はDbUpdateクラスのメソッドを呼び出す。

---
