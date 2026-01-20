# server_list.js
対象: `src/main/resources/static/server_list.js`

## 概要
- サーバー管理画面の一覧・検索・ソート・ページング・有効/無効化操作を提供するフロントエンドモジュール。`ListViewCore` を利用し、状態をURLクエリに保持する。

## 主な機能
- `/api/servers` への検索・ソート・ページング付き取得（q, sort, order, page, size）。
- テーブル描画とヘッダソート（data-column）＋ソート矢印表示。
- 行クリックでミニメニュー表示（有効化/無効化）し、確認モーダル経由で POST 実行。
- 管理者判定（`#current-user-admin` の data-is-admin）に基づきメニュー項目を disabled/hidden。
- `ListViewCore` 未ロード時の遅延ロード試行と初期化リトライ。

## 挙動
- 初期ソートは `serverName`、1ページ20件固定。検索は250msデバウンスして `ListViewCore.reload(1)` を呼ぶ。
- API レスポンス `{servers,total,page,size,totalPages}` を受け取り、クライアント側で現行ソートキーに合わせて描画。
- 無効化: `/api/servers/{id}/disable`、有効化: `/api/servers/{id}/enable` を POST。401 はログインへリダイレクト。
- 行は `isActive=false` の場合 `.inactive` でグレー表示。

## 細かい指定された仕様
- 必須DOM: `#server-q`, `#server-pagination`, `#server-results-body`, ヘッダ `#server-results th[data-column]`。
- ミニメニュー: `mini_menu.js` 生成のメニューをクリック座標で表示。非管理者はメニュー disabled。
- 確認モーダル: `#confirm-modal` / `#confirm-backdrop` を hidden クラスで制御。
- 依存: `list_view_core.js`, `mini_menu.js`, `script.js`。

## その他
- `ListViewCore` 未ロード時は `window.loadScript('/static/list_view_core.js')` を試行し、最大30回/3秒リトライで初期化。

## 変更履歴
- 2026-01-20: ListViewCore ベースに移行し、URLクエリ同期・遅延ロード・リトライを追記。列ソートと管理者ガードの仕様を更新。
- 2026-01-09: 非管理者の操作ガード追加、schedule_add 廃止。

## コミットメッセージ例
- docs(web): server_list.js を ListViewCore 仕様に更新
