# WhitelistSettingsService
対象: src/main/java/com/edamame/web/service/WhitelistSettingsService.java
## 概要
- ホワイトリスト設定（モードとIP一覧）の取得・更新・差分算出を担うサービス。
## 主な機能
- settingsテーブルから whitelist_mode と whitelist_ip を読み出しDTO化。
- 更新時に入力IPを正規化し、DBへ保存。
- 更新前後の差分（モード変更/追加IP/削除IP）を算出して返却。
## 挙動
- load() で DB設定を取得し、カンマ区切りIP文字列を正規化してリスト化。
- update() で更新前設定を読み込み、入力IPを検証・重複排除し、DB更新後に差分をレコードで返す。
- IP検証は英数字/コロン/ドット/カンマ以外を拒否し、InetAddress.getByName で最終チェック。
## 細かい指定された仕様
- DB更新は DbService.updateWhitelistSettings(boolean mode, String commaIps) を使用。
- IP入力はnull→空、カンマ区切り複数を許容。重複は保持せず順序は保持（LinkedHashSet）。
- バリデーションエラー時は IllegalArgumentException を送出。
## メソッド一覧
- WhitelistSettings load() 設定取得。
- WhitelistUpdateResult update(boolean mode, List<String> ips, String updatedBy) 更新＋差分返却。
- List<String> normalizeIps(List<String> rawIps) IPリスト正規化・重複排除。
- oid validateIp(String ip) IP書式検証。
## その他
- 差分は controller 層で監査メール送信に利用。
## 変更履歴
- 2026-02-11: 差分返却のDTOを追加し監査通知向けの情報を提供、初版ドキュメント作成。

