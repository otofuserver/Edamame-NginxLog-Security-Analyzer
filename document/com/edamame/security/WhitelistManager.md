# WhitelistManager

対象: `src/main/java/com/edamame/security/WhitelistManager.java`

## 概要
- ホワイトリスト（IP もしくは URL）管理用ユーティリティ。DB 上の設定を参照してクライアント IP がホワイトリストに該当するか判定したり、アクセス時に URL のホワイトリスト状態を更新する補助処理を提供する。

## 主な機能
- クライアント IP によるホワイトリスト判定（`determineWhitelistStatus`）
- 既登録 URL のアクセス時にホワイトリスト状態を更新する補助（`updateExistingUrlWhitelistStatusOnAccess`）

## 挙動
- DB の `selectWhitelistSettings()` を参照して `whitelist_mode` と `whitelist_ip` を取得し、カンマ区切りの IP 列と照合する。
- 例外時はログ出力してデフォルトで false を返す（ホワイトリスト適用なし）。

## 細かい指定された仕様
- `whitelist_ip` はカンマ区切りの IP リストを想定する。
- ホワイトリスト処理は DB アクセスに依存するため例外発生時は fail-safe（false）を返す設計。

## メソッド一覧と機能
- `public WhitelistManager()` - コンストラクタ
- `public boolean determineWhitelistStatus(String clientIp)` - IP ベースの判定を行う
- `public void updateExistingUrlWhitelistStatusOnAccess(String serverName, String method, String fullUrl, String clientIp)` - アクセス時の URL ホワイトリスト更新補助

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(security): WhitelistManager の仕様書を追加

