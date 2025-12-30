# DbSelect

対象: `src/main/java/com/edamame/security/db/DbSelect.java`

## 概要
- データベースの SELECT 系処理を集約するユーティリティクラス。`DbSession` を受け取り Connection を直接扱うことなく SQL 実行結果を返却する。

## 主な機能
- サーバー情報取得（`selectServerInfoByName`）
- サーバ存在判定（`existsServerByName`）
- pending ブロック要求の取得（`selectPendingBlockRequests`）
- settings テーブルからホワイトリスト設定取得（`selectWhitelistSettings`）
- url_registry 関連の問い合わせ（`existsUrlRegistryEntry`, `selectIsWhitelistedFromUrlRegistry`）
- ModSecurity 照合用の最近のアクセスログ取得（`selectRecentAccessLogsForModSecMatching`）

## 挙動
- 各メソッドは `DbSession.executeWithResult` を用いて例外処理をラップし、ResultSet を Map や DTO に変換して返す。
- SQL 実行時の SQLException は RuntimeException としてラップされ上位へ伝播する設計。

## 主なメソッド
- `public static Optional<ServerInfo> selectServerInfoByName(DbSession dbSession, String serverName)`
- `public static boolean existsServerByName(DbSession dbSession, String serverName)`
- `public static List<Map<String,Object>> selectPendingBlockRequests(DbSession dbSession, String registrationId, int limit)`
- `public static Map<String,Object> selectWhitelistSettings(DbSession dbSession)`
- `public static boolean existsUrlRegistryEntry(DbSession dbSession, String serverName, String method, String fullUrl)`
- `public static Boolean selectIsWhitelistedFromUrlRegistry(DbSession dbSession, String serverName, String method, String fullUrl)`
- `public static List<Map<String,Object>> selectRecentAccessLogsForModSecMatching(DbSession dbSession, int minutes)`

## 変更履歴
- 2.1.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(db): DbSelect の仕様書を追加

