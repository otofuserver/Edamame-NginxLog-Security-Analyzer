# DbSelect.java 仕様書

## 役割
- データベースからの検索・SELECT処理を担当。
- 各種テーブルの検索・集計・条件抽出を実装。
- サーバー・エージェント等のSELECT系処理を集約。

## 主なメソッド
- `public static Optional<ServerInfo> selectServerInfoByName(Connection conn, String serverName)`
  - サーバー名でサーバー情報（id, server_description, log_path）を取得。
  - 結果はOptional<ServerInfo>で返却。
- `public static boolean existsServerByName(Connection conn, String serverName)`
  - サーバー名でserversテーブルの存在有無を判定。
  - 結果はbooleanで返却。
- `public static record ServerInfo(int id, String description, String logPath)`
  - サーバー情報DTO。
- `public static List<Map<String, Object>> selectPendingBlockRequests(Connection conn, String registrationId, int limit)`
    - 指定したregistrationIdに紐づく、status='pending'のブロック要求リストを取得。
    - 取得カラム: request_id, ip_address, duration, reason, chain_name
    - 取得順: created_at昇順
    - パラメータ:
        - conn: データベース接続
        - registrationId: エージェント登録ID
        - limit: 最大取得件数
    - 返却値: 各要求をMap<String, Object>で表現したリスト
    - 用途: エージェントへのブロック指示送信処理等で利用
    - 注意: 例外はSQLExceptionとしてスロー。呼び出し元でハンドリング。
- `public static Map<String, Object> selectWhitelistSettings(Connection conn)`
    - settingsテーブルからwhitelist_mode, whitelist_ipを取得。
    - 返却値: Map（whitelist_mode: Boolean, whitelist_ip: String）
    - 用途: ホワイトリスト判定処理等で利用
    - 注意: 例外はSQLExceptionとしてスロー。呼び出し元でハンドリング。
- `public static boolean existsUrlRegistryEntry(Connection conn, String serverName, String method, String fullUrl)`
  - url_registryテーブルに(server_name, method, full_url)が存在するか判定。
  - 結果はbooleanで返却。
  - 用途: URL登録処理の重複チェックや既存URLのホワイトリスト再評価処理等で利用
  - 例外はSQLExceptionとしてスロー。呼び出し元でハンドリング。
- `public static Boolean selectIsWhitelistedFromUrlRegistry(Connection conn, String serverName, String method, String fullUrl)`
  - url_registryテーブルからis_whitelistedカラムの値を取得。
  - レコードが存在しない場合はnullを返却。
  - 用途: 既存URLのホワイトリスト状態再評価処理等で利用。
  - 例外はSQLExceptionとしてスロー。呼び出し元でハンドリング。

## ロジック
- プリペアドステートメントで安全にSELECT実行。
- サーバー名の照合順序（utf8mb4_unicode_ci）に対応。
- 結果がなければOptional.empty()またはfalse/nullを返却。
- 例外は呼び出し元でハンドリング。

## 注意事項
- SELECT対象カラム・テーブル追加時は本クラスの対応も必ず追加すること。
- 仕様変更時は本仕様書・実装・db_schema_spec.md・CHANGELOG.mdを同時更新。

---

### 参考：実装例
- selectServerInfoByName, existsServerByName, selectWhitelistSettings などをpublic staticで実装。
- DTOはJava recordで定義。

---
