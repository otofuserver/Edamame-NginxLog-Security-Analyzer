# DbRegistry.java 仕様書

## 役割
- サーバー情報（serversテーブル）の登録・新規追加・存在確認・自動登録を担当。
- サーバーの有効/無効切り替えや説明文の更新。
- agent_serversテーブルのエージェント登録・状態管理（ON DUPLICATE KEY UPDATEによる再登録も担当）。
- ※一部のUPDATE系処理（サーバー情報・エージェント状態・統計更新など）はDbUpdate.javaに移譲。

## 主なメソッド
- `public static void registerOrUpdateServer(Connection conn, String serverName, String description, String logPath, BiConsumer<String, String> logFunc)`
  - サーバー名で存在確認し、未登録ならINSERT、既存ならDbUpdate.updateServerInfo()でUPDATE。
  - サーバー名の照合順序（utf8mb4_unicode_ci）に対応。
- `public static String registerOrUpdateAgent(Connection conn, Map<String, Object> serverInfo, BiConsumer<String, String> logFunc)`
  - agent_serversテーブルにエージェント情報を登録または更新。
  - ON DUPLICATE KEY UPDATEで再登録時も情報更新。
  - 登録IDは「agent-{timestamp}-{random}」形式で自動生成。

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
- UPDATE系処理はDbUpdateクラスに委譲。
- ログ出力はBiConsumer<String, String> logFuncでINFO/DEBUG/ERROR/WARNを明示。

## 注意事項
- サーバー名の照合順序（utf8mb4_unicode_ci）に必ず対応すること。
- agent_serversの登録IDはgenerateAgentRegistrationId()で一意生成。
- テーブル・カラム追加時は本クラスの対応も必ず追加すること。
- 例外時はcatchでエラーログを出力し、必要に応じてリトライ。
- UPDATE系のDB処理はDbUpdate.javaに集約。

---

### 参考：実装例
- registerOrUpdateServer, registerOrUpdateAgent などをpublic staticで実装。
- agent_serversのON DUPLICATE KEY UPDATEで再登録時も情報更新。
- ログ出力はINFO/DEBUG/ERROR/WARNで明確に記録。
- UPDATE系はDbUpdateクラスのメソッドを呼び出す。

---
