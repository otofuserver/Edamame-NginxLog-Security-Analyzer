# url_threat.js

対象: `src/main/resources/static/url_threat.js`

## 概要
- URL脅威度ビューのフロントエンドロジック。`ListViewCore` を利用して検索・フィルタ・ソート・ページングをURLクエリに保持し、ミニメニューとノートモーダルで脅威分類操作を行う。

## 主な機能
- サーバー選択・脅威度フィルタ（all/danger/caution/unknown/safe）・検索（q）・ソート（priority/url/method/attack/latest_access/status/blocked）・ページングをまとめて `/api/url-threats` へリクエスト。
- テーブル描画とソート矢印表示、列幅は `list_views.css` のクラス（threat-col/status-col/modsec-col等）で制御。
- 行クリックでミニメニュー表示（コピー/危険/安全/解除/理由確認）、`canOperate` に応じて hidden/disabled 切替。
- ノートモーダルで危険/安全/解除/理由確認のPOSTを行い、完了後に一覧を再読込。

## 挙動
- 初期ソートは `priority` (order=asc)、1ページ20件固定。サーバー/フィルタ/検索は URL クエリに保持され、F5後も復元。
- API レスポンス `{items,total,page,size,totalPages,canOperate}` を用い、`ListViewCore` が pager/renderRows をハンドリング。`STATE.canOperate` でミニメニュー表示状態を制御。
- サーバー未選択時はメッセージを出し、空リストを返して処理を終了。

## 細かい指定された仕様
- 必須DOM: `#url-threat-q`, `#url-threat-server`, `#url-threat-pager`, `#url-threat-body`, ヘッダ `#url-threat-table th.sortable[data-column]`。
- フィルタラジオ: `name="url-threat-filter"` の change で filter を更新し再読込。
- ミニメニュー: `mini_menu.js` による `mini-menu` を使用。座標は click event の pageX/pageY。
- モーダル: `#url-threat-modal-backdrop` と `#url-threat-note-modal` を hidden クラスで開閉。閲覧のみの場合は textarea/readOnly, 保存ボタン disabled。
- 依存: `list_view_core.js`, `mini_menu.js`, `script.js`。

## その他
- `ListViewCore` 未ロード時は `window.loadScript('/static/list_view_core.js')` を試行し、初期化を遅延。

## 変更履歴
- 2026-01-20: ListViewCore 連携と URL クエリ同期に刷新。localStorage 依存を廃止し、ヘッダ data-column を追加。
- 2026-01-20: ミニメニュー/モーダルの権限制御と列幅 (`list_views.css`) を更新。
- 2026-01-15: 初版。`url_registry` 最新メタを用いた一覧とミニメニューを実装。
