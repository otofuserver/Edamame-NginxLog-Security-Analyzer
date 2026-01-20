# user_list.js (ユーザー一覧)
対象: `src/main/resources/static/user_list.js`

## 概要
- ユーザー管理画面の一覧・検索・ソート・ページングを担当するフロントエンドモジュール。`ListViewCore` を用いて状態をURLクエリに保持し、`window.UserList` API を提供する。

## 主な機能
- `/api/users` への検索・ソート・ページング付き取得（q, sort, order, page, size）。
- テーブル描画と列ソートインジケータ更新（ヘッダ data-column を使用）。
- 行クリックで `UserModal.openUserModal` を呼び出す詳細表示、作成ボタンから `openCreateUserModal` を起動。
- 初期化時のDOM/スクリプト準備リトライ、`list_view_core.js` の遅延読み込みフォールバック。

## 挙動
- 初期ソートは `username`、1ページ20件固定。検索は250msデバウンスしURLクエリへ反映、F5後も同条件で復元。
- APIレスポンス `{users,total,page,size,totalPages}` を受け取り、フロント側で現行ソートキーに合わせて描画する（ソートキーが日付/booleanの場合は型変換して比較）。
- 401は考慮外（呼び出し元でハンドリング）。fetch失敗時はテーブルにエラーメッセージ行を表示。

## 細かい指定された仕様
- 必須DOM: `#q`, `#pagination`, `#user-results-body`。ヘッダは `#user-results th[data-column]`。
- ボタン: 検索フォーム直上に「ユーザー作成」ボタンを挿入（存在しない場合のみ）。
- 依存: `list_view_core.js`（必須）、`user_modal.js`（モーダル呼び出し）、`script.js`（ブートストラップ）。

## その他
- `ListViewCore` 未ロード時は `window.loadScript('/static/list_view_core.js')` を試行し、最大30回/3秒リトライで初期化。

## 変更履歴
- 2026-01-20: ListViewCore ベースに移行し、URLクエリ同期と自動リトライを記載。
- 2025-12-29: 初版（ローカルソート/ページング中心）。

## コミットメッセージ例
- docs(web): user_list.js の仕様書を統一フォーマットへ変換
