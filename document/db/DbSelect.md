# DbSelect.java 仕様書

## 役割
- データベースからの検索・SELECT処理を担当。
- 各種テーブルの検索・集計・条件抽出を実装。
- サーバー・エージェント等のSELECT系処理を集約。
- DbServiceとDbSessionを使用してConnection引数を完全排除（v2.0.0）。

## 主なメソッド（v2.0.0 - Connection引数完全廃止）

### DbService使用のメソッド（Connection引数なし）
- `public static Optional<ServerInfo> selectServerInfoByName(DbService dbService, String serverName)`
  - サーバー名でサーバー情報（id, server_description, log_path）を取得（DbService使用）。
  - 結果はOptional<ServerInfo>で返却。
  - DbService.selectServerInfoByName()に委譲。
- `public static boolean existsServerByName(DbService dbService, String serverName)`
  - サーバー名でserversテーブルの存在有無を判定（DbService使用）。
  - 結果はbooleanで返却。
  - DbService.existsServerByName()に委譲。
- `public static List<Map<String, Object>> selectPendingBlockRequests(DbService dbService, String registrationId, int limit)`
  - 指定したregistrationIdに紐づく、status='pending'のブロック要求リストを取得（DbService使用）。
  - DbService.selectPendingBlockRequests()に委譲。
- `public static Map<String, Object> selectWhitelistSettings(DbService dbService)`
  - settingsテーブルからwhitelist_mode, whitelist_ipを取得（DbService使用）。
  - DbService.selectWhitelistSettings()に委譲。
- `public static boolean existsUrlRegistryEntry(DbService dbService, String serverName, String method, String fullUrl)`
  - url_registryテーブルに(server_name, method, full_url)が存在するか判定（DbService使用）。
  - DbService.existsUrlRegistryEntry()に委譲。
- `public static Boolean selectIsWhitelistedFromUrlRegistry(DbService dbService, String serverName, String method, String fullUrl)`
  - url_registryテーブルからis_whitelistedカラムの値を取得（DbService使用）。
  - DbService.selectIsWhitelistedFromUrlRegistry()に���譲。

## DTOクラス
- `public record ServerInfo(int id, String description, String logPath)`
  - サーバー情報DTO。Java recordで実装。

## ロジック
- **完全DbService委譲**: すべてのメソッドがDbServiceインスタンスを受け取り、内部的にDbServiceのメソッドに委譲。
- **Connection引数完全排除**: v2.0.0でConnection引数を使用するメソッドを完全廃止。
- **統一性重視**: 古いConnection引数方式との互換性を維持せず、一気に入れ替えで統一性を確保。
- **例外処理**: SQLException は呼び出し元でハンドリング。
- **結果処理**: データが見つからない場合はOptional.empty()、false、nullを適切に返却。

## 使用例（v2.0.0完全版）

### 新しいDbService専用版
```java
try (DbService dbService = new DbService(url, properties, logger)) {
    // サーバー情報取得
    Optional<DbSelect.ServerInfo> info = DbSelect.selectServerInfoByName(dbService, "web-server-01");
    
    // 存在確認
    boolean exists = DbSelect.existsServerByName(dbService, "web-server-01");
    
    // ブロック要求取得
    List<Map<String, Object>> requests = DbSelect.selectPendingBlockRequests(dbService, "agent-123", 10);
    
    // ホワイトリスト設定取得
    Map<String, Object> settings = DbSelect.selectWhitelistSettings(dbService);
    
    // URL存在確認
    boolean urlExists = DbSelect.existsUrlRegistryEntry(dbService, "web-server-01", "GET", "/api/users");
    
    // ホワイトリスト状態取得
    Boolean isWhitelisted = DbSelect.selectIsWhitelistedFromUrlRegistry(dbService, "web-server-01", "GET", "/api/users");
}
```

## 移行完了方針
- **v2.0.0**: Connection引数を完全廃止、DbService専用に統一
- **互換性なし**: 古いConnection引数方式は完全削除済み
- **統一性確保**: 無駄を省き、一貫したAPI設計を実現

## 注意事項
- **DbService必須**: すべてのメソッドでDbServiceインスタンスが必要。
- **Connection引数廃止**: v2.0.0でConnection引数を使用するメソッドは完全削除済み。
- **SELECT対象追加時**: 本クラスの対応も必ず追加すること。
- **仕様変更時**: 本仕様書・実装・db_schema_spec.md・CHANGELOG.mdを同時更新。

## バージョン履歴
- **v2.0.0** (2025-01-11): Connection引数を完全廃止、DbService専用に統一。互換性なし一気切り替え完了
- **v1.2.0** (2025-01-11): DbServiceとDbSessionを使用したConnection引数なしメソッドを追加
- **v1.1.0** (2025-01-11): ビルドエラー修正完了
- **v1.0.0**: 初期実装

---

### 参考：実装例
- selectServerInfoByName, existsServerByName, selectWhitelistSettings などをpublic staticで実装。
- DTOはJava recordで定義。
- DbService使用版は内部的にDbServiceのメソッドに委譲。
- Connection引数方式は完全削除済み。

---
