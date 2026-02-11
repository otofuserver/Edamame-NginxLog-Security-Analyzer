# whitelist_settings フラグメント

対象: `src/main/resources/fragments/whitelist_settings.html`

## 概要
- ホワイトリスト設定画面の断片HTML。admin専用でモード切替と許可IP管理を提供する。

## 主な機能
- ホワイトリストモードON/OFFスイッチを表示。
- 許可IPの追加入力欄と「追加」ボタンを提供（カンマ区切り複数可）。
- 許可IP一覧テーブルと削除ボタンを表示。

## 挙動
- 初期表示時に `whitelist_settings.js` を読み込み、`WhitelistSettings.init()` を即時実行。
- 追加ボタンは見出し行（入力欄の上）に配置し、クリックで入力欄の内容を処理。
- 一覧は `list_view_core.js` とJS側の描画で更新。

## 細かい指定された仕様
- スイッチID: `whitelist-mode-toggle`。状態表示: `whitelist-mode-status`。
- 追加入力ID: `whitelist-ip-input`、追加ボタンID: `whitelist-add-btn`、エラー表示ID: `whitelist-error`。
- 一覧テーブルtbody ID: `whitelist-tbody`。操作列に削除ボタンを設置。
- data-viewは外部のナビゲーションが設定する前提。

## その他
- スタイルは既存のカード/テーブルクラスを継承。追加ボタン位置を入力欄上に配置するため `add-ip-header` にflex指定。

## 変更履歴
- 2026-02-11: 追加ボタンを入力欄上部に移動。初版ドキュメント作成。

