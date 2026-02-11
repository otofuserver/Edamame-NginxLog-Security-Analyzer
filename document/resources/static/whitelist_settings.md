# whitelist_settings.js

対象: `src/main/resources/static/whitelist_settings.js`

## 概要
- ホワイトリスト設定フロントエンドのクライアントロジック。モード切替とIP追加/削除をAPI経由で反映し、一覧を描画する。

## 主な機能
- `/api/whitelist-settings` から設定を取得し、UIに反映。
- モードON/OFF操作とIP追加/削除をPUTで保存。
- `list_view_core` を用いて一覧を再描画。
- 入力検証とエラー表示。

## 挙動
- DOMContentLoaded後に `WhitelistSettings.init()` が呼ばれる前提。initでイベントバインド・初回取得を実行。
- 追加ボタン押下で入力欄のカンマ区切りIPを検証し、重複を除外して保存。
- 削除ボタン押下で対象IPを除去し保存。
- 保存成功時は最新状態をUIに反映（ON/OFF表示、テーブル再描画）。通信失敗時はエラーを表示。

## 細かい指定された仕様
- APIエンドポイント: GET/PUT `/api/whitelist-settings`（same-origin）。
- リクエストボディ例: `{ "whitelistMode": true, "whitelistIps": ["192.0.2.10", "2001:db8::1"] }`。
- 入力検証: `/^[0-9a-fA-F:.,]+$/` にマッチしない場合はエラー。
- UI要素ID: `whitelist-mode-toggle`, `whitelist-mode-status`, `whitelist-ip-input`, `whitelist-add-btn`, `whitelist-error`, `whitelist-tbody`。
- エラー表示は `whitelist-error` を表示/非表示で制御。

## メソッド一覧
- `init()` 初期化（イベント登録、初回ロード）。
- `loadSettings()` 設定取得・UI反映。
- `saveSettings()` 現在STATEをPUT保存。
- `addIps(newIps)` 入力IPを検証しSTATEに追加・保存。
- `removeIp(ip)` STATEから除去し保存。
- `parseInputIps()` 入力欄からカンマ区切りで配列化。
- `validateIp(ip)` 書式検証。
- `buildListView()` `ListViewCore` を初期化し一覧を描画。

## その他
- STATEは `{ mode: boolean, ips: [] }` を保持し、再描画時に利用。
- API失敗時はUIのみエラー表示し、例外はコンソールに出さない設計。

## 変更履歴
- 2026-02-11: ホワイトリスト設定UI初期実装、仕様書新規作成。

