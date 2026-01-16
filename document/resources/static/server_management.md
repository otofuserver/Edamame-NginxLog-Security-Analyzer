# server_management.html

対象: `src/main/resources/fragments/server_management.html`

## 概要
- サーバー管理の断片 (fragment)。サーバー一覧の検索・表示・ページング・行クリックによるコンテキストメニューや無効化/有効化の操作 UI を提供する。

## 主な機能
- 検索ボックス (`#server-q`) でサーバー名による部分検索
- 結果テーブル (`#server-results-body`) にて最大20件を表示、ページングを行う（`#server-pagination`）
- 行クリックで `#server-context-menu` を表示し、無効化/有効化 操作をトリガする
- 確認モーダル (`#confirm-modal`) を通じて重大操作の確認を行う

## 挙動
- 初回ロード時は `ServerList.initServerManagement(initialQ)` によって JS 側でイベントバインドと検索を行う（`script.js` 経由で `server_list.js` を読み込んで初期化）。
- `is_active=false` の行は `.inactive` クラスを付与して灰色表示にする。
- コンテキストメニューはメニュー風スタイルで、ホバー・クリックの挙動が自然に見えるように設計されている。

## 細かい指定された仕様
- テーブルヘッダは `th[data-column]` 属性で列ソートが可能。クリックで `server_list.js` の `attachHeaderSortHandlers` がトリガされる（選択中カラムは ▲/▼ を表示）。
- `#server-context-menu` 内の「無効化」ボタンは管理者以外に対して視覚的に無効化（`.btn-disabled`）される。実行時にはフロントと API 両方でガードされる。
- `#confirm-modal` は操作の最終確認に使用される。OK が押されると登録されたコールバックが実行される。

## その他
- この断片は `fragmentService.getFragmentTemplate("server_management")` 経由で `ApiController` から返される。

## 変更履歴
- 2026-01-09: 「後で追加」メニューを削除。コンテキストメニューをメニュー風の見た目に改善。
- 2026-01-09: 非管理者向けの無効化スタイルとフロントガードを追加。
- 2026-01-16: ソート方向を示す矢印（▲/▼）をヘッダに表示。

## コミットメッセージ例
- docs(front): server_management.html の仕様書を追加（コンテキストメニュー改善、schedule_add 廃止を反映）
