# list_view_core.js
対象: `src/main/resources/static/list_view_core.js`

## 概要
- 一覧画面の共通ロジックを提供する軽量ヘルパー。検索・ソート・ページング状態を URL クエリに同期し、描画とページネーションを呼び出し元コールバックへ委譲する。

## 主な機能
- 状態管理: `q`, `sort`, `order`, `page`, `size`, `total`, `totalPages` を保持し、`window.history.replaceState` で URL クエリを更新。
- ソート: `headerSelector` で指定した th[data-column] クリックにより `sort`/`order` をトグルし、矢印付与。
- ページング: `pagerEl` へ « ‹ current › » ボタンを生成し、ページ変更時に `reload(nextPage)` を呼ぶ。
- 検索: `searchInput` へデバウンス入力ハンドラを設定し、Enter抑止＋自動再検索。
- フック: `fetcher(params)` / `renderRows(items,state)` / `applyStateToUi(state)` / `extractFilters()` / `onStateChange(state)` を呼び出し元で指定。

## 挙動
- 初期化 `createListView({ ... }).init()` で URL から `q/sort/order/page` を読み取り、UIへ反映後に `reload`。
- `reload(nextPage)` は `fetcher` を await し、戻り値 `{items,total,page,size,totalPages}` を state へ反映。失敗時は console.error のみ。
- ソート矢印は th の元テキストを `data-label-original` に保存し、現在の `order` に応じ ▲/▼ を付与。

## 細かい指定された仕様
- `fetcher` 引数には `q, sort, order, page, size` に加え `extractFilters()` の戻り値がマージされる。
- `pagerEl` が存在しない場合はページネーションを描画しない（デグレースフル）。
- `renderRows` 側で行クリックや色付けを実装する想定。state は `{q,sort,order,page,size,total,totalPages}` を保持。
- 例外は握りつぶしではなく `console.error('ListViewCore reload error', e);` を出力する。

## その他
- optional chaining / nullish coalescing を使用せず、レガシー環境でも解釈できる構文。
- グローバルエクスポート: `window.ListViewCore = { createListView }`。

## 変更履歴
- 2026-01-20: 新規作成。リスト共通化・URLクエリ同期・pager/ソート矢印生成を実装。

## コミットメッセージ例
- docs(web): add list_view_core.js spec
