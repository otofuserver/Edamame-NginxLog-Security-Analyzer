# WhitelistManager.java 仕様書

## 役割
- ホワイトリスト（IPアドレス・URL）判定・管理処理を集約するクラス。
- DBからホワイトリスト設定を取得し、IPアドレスやURLのホワイトリスト判定・状態更新を行う。
- 既存のホワイトリスト関連ロジックを一元化し、今後の拡張・保守性を高める基盤クラス。

## 主なメソッド（v1.0.0）

- `public WhitelistManager(DbService dbService, BiConsumer<String, String> logFunction)`
  - コンストラクタ。DBサービスとログ出力関数を受け取る。
- `public boolean determineWhitelistStatus(String clientIp)`
  - クライアントIPがホワイトリスト対象かどうかを判定する。
  - DBのsettingsテーブルからwhitelist_mode, whitelist_ipを取得し、カンマ区切りで複数IP対応。
  - 判定結果をログ出力し、true/falseで返却。
- `public void updateExistingUrlWhitelistStatusOnAccess(String serverName, String method, String fullUrl, String clientIp)`
  - 既存URLの再アクセス時にホワイトリスト状態を再評価し、必要に応じてDBのurl_registryテーブルのis_whitelistedを更新する。
  - 既に安全判定済みの場合は何もしない。
  - 判定・更新結果をログ出力。

## ロジック
- DBアクセスはDbService経由で行う。
- ホワイトリスト判定はsettingsテーブルのwhitelist_mode, whitelist_ipを参照。
- IPアドレスはカンマ区切りで複数指定可能。
- 既存URLのホワイトリスト状態はurl_registryテーブルのis_whitelistedで管理。
- 例外発生時はエラーログを出力し、判定はfalseまたは何もしない。

## 使用例
```java
DbService dbService = ...;
BiConsumer<String, String> log = ...;
WhitelistManager manager = new WhitelistManager(dbService, log);

boolean isWhite = manager.determineWhitelistStatus("192.168.1.10");
manager.updateExistingUrlWhitelistStatusOnAccess("web-server-01", "GET", "/api/users", "192.168.1.10");
```

## 注意事項
- DBアクセスは必ずDbService経由で行うこと。
- ホワイトリスト設定・判定ロジックを拡張する場合は本クラスに集約すること。
- 仕様変更時は本仕様書・実装・関連ドキュメントを同時更新すること。

## バージョン履歴
- **v1.0.0** (2025-08-12): 初版作成、AgentTcpServerからホワイトリスト判定・状態更新ロジックを集約

---

### 参考：実装例
- determineWhitelistStatus, updateExistingUrlWhitelistStatusOnAccess をpublicで実装。
- DBアクセスはDbService経由。
- ログ出力はBiConsumer<String, String>で統一。

