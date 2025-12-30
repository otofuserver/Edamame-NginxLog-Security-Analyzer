# DbDelete

対象: `src/main/java/com/edamame/security/db/DbDelete.java`

## 概要
- 古いログや履歴データを定期的に削除するバッチ処理（ログクリーンアップ）を提供するユーティリティ。
- `settings` テーブルの `log_retention_days` を参照して、古い `access_log`, `modsec_alerts`, `agent_servers` などを削除する。

## 主な機能
- ログクリーンアップバッチの実行（`runLogCleanupBatch`）
- 古い ModSecurity アラート、アクセスログ、エージェント情報、ログイン履歴、アクション実行ログの削除
- サーバー単位データ削除（`deleteServerData`）および関連テーブルの一括削除処理

## 挙動
- `runLogCleanupBatch` は `settings` から保持日数を取得し、各削除処理を順に実行する。例外はキャッチしてログを残し継続する実装。
- 削除対象は日付比較（`DATE_SUB(NOW(), INTERVAL ? DAY)`）で判定される。
- `deleteServerData` はトランザクション内で ModSecurity アラート→access_log→url_registry→users_roles→roles→servers の順に削除を行う。

## 細かい指定された仕様
- 削除処理はトランザクションで実行される箇所と、単発 DELETE で問題とならない箇所に分かれている。
- 大量削除は DB 負荷を招くため、スロットリングや分割削除の検討を推奨する。
- 削除前にバックアップ（例：定期スナップショット）を取得する運用を推奨。

## メソッド一覧と機能（主なもの）
- `public static void runLogCleanupBatch(DbSession dbSession)` - ログクリーンアップのエントリーポイント。
- `private static void deleteOldModSecAlerts(DbSession dbSession, int retentionDays)`
- `private static void deleteOldAccessLogs(DbSession dbSession, int retentionDays)`
- `private static void deleteOldAgentServers(DbSession dbSession, int retentionDays)`
- `private static void deleteOldLoginHistory(DbSession dbSession, int retentionDays)`
- `private static void deleteOldActionExecutionLog(DbSession dbSession, int retentionDays)`
- `public static void deleteServerData(DbSession dbSession, String serverName)` - サーバー単位の一括削除エントリ。
- `private static void deleteModSecAlertsByServer(DbSession dbSession, String serverName)`
- `private static void deleteAccessLogsByServer(DbSession dbSession, String serverName)`
- `private static void deleteUrlRegistryByServer(DbSession dbSession, String serverName)`
- `private static void deleteUsersRolesByServer(DbSession dbSession, String serverName)`
- `private static void deleteRolesByServer(DbSession dbSession, String serverName)`
- `private static void deleteServerByName(DbSession dbSession, String serverName)`

## その他
- 削除系処理はリスクが高いため、実行前に監査ログを残し、オフラインでのリストア手順をドキュメント化すること。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（ソースに基づく）

## コミットメッセージ例
- docs(db): DbDelete の仕様書を追加

