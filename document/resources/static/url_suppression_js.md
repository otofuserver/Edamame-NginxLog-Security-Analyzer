# url_suppression.js
対象: `src/main/resources/static/url_suppression.js`

## 概要
- URL抑止管理ビューのフロントエンドロジック。`ListViewCore` を用いて検索・ソート・ページングの状態をURLクエリに保持し、ミニメニュー/モーダルを介して `/api/url-suppressions` と連携する。

## 主な機能
- サーバー一覧取得とセレクト初期化（`/api/servers?size=200`）。
- `/api/url-suppressions` への検索・フィルタ（server）・ソート・ページング取得。
- 行クリックでのミニメニュー表示（有効/無効切替・編集・削除）。
- 作成/編集モーダルの表示・保存（POST/PUT）・削除（DELETE）。
- `canEdit` に基づくミニメニュー項目・ボタンの表示制御。

## 挙動
- 初期ソートは `updatedAt`、1ページ20件固定。検索は `q` 入力をデバウンスし、サーバーフィルタ変更時は page=1 で再検索。URLクエリに `q`, `server`, `sort`, `order`, `page`, `size` を保持。
- API レスポンス `{items, total, page, size, totalPages, canEdit}` を受け取り、ヘッダ data-column に従い描画・ソート矢印を更新。
- 行 `isEnabled=false` は `.inactive` で薄表示。削除時は confirm で確認後、完了したらリロード。

## 細かい指定された仕様
- 必須DOM: `#url-suppression-q`, `#url-suppression-server`, `#url-suppression-pager`, `#url-suppression-body`, ヘッダ `#url-suppression-results th[data-column]`。
- ミニメニュー: `mini_menu.js` による `mini-menu` を使用、座標は click event の pageX/pageY を使用。
- モーダル: `aria-hidden` と display で開閉。`canEdit=false` の場合、削除ボタンやミニメニュー項目を hidden/disabled。
- 依存: `list_view_core.js`, `mini_menu.js`, `script.js`。

## その他
- `ListViewCore` 未ロード時は `window.loadScript('/static/list_view_core.js')` を試行し、初期化リトライで最大30回/3秒。

## 変更履歴
- 2026-01-20: ListViewCore 連携・URLクエリ同期・デバウンス検索を反映。`canEdit` 制御とミニメニュー仕様を更新。
