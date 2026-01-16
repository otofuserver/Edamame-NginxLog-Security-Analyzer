# server_list.js

対象: `src/main/resources/static/server_list.js`

## 概要
- サーバー管理画面のクライアントモジュール。サーバーの一覧取得・検索・ページング・行クリックによるコンテキストメニュー表示・無効化/有効化操作等を提供する。

## 主な機能
- サーバー一覧の取得（`/api/servers`）およびレンダリング
- 検索ボックス（`#server-q`）の入力に応じたフィルタリング呼び出し
- ページング（20件/ページ）とソート（列ヘッダクリック）
- 行クリックでのコンテキストメニュー表示（無効化/有効化）と確認モーダル
- 管理者フラグに基づく UI 制御（`#current-user-admin` の data-is-admin を参照）

## 挙動
- 初期化は `ServerList.initServerManagement(initialQ)` で行う。
- 検索は `doSearch(page)` を通じて `/api/servers` を呼び、JSON 結果を `render()` で描画する。
- `render()` は受け取ったサーバ配列をテーブル行に変換し、`lastLogReceived` をローカル日時文字列に整形して表示する。
- 行をクリックすると `showContextMenu()` が呼ばれ、選択サーバの状態に応じて「無効化」「有効化」を切り替えて表示する。
- 右メニューの「無効化/有効化」ボタンは、非管理者の場合は `disabled` 属性と `.btn-disabled` クラスを付与して視覚的・操作的に無効化される。
- 無効化/有効化ボタンはクリック時に確認モーダル (`#confirm-modal`) を表示し、確認後に `/api/servers/{id}/disable` または `/api/servers/{id}/enable` へ POST する。

## 細かい指定された仕様
- 1ページあたりの件数は `20`（UI 表示メッセージにも明記）。
- 行は `is_active=false` の場合 `.inactive` クラスを付与し、CSSで薄めに表示する。
- 検索入力はデバウンス（250ms）で自動検索を実行する。
- 取得する JSON の構造: `{ servers: [...], total: <int>, page: <int>, size: <int> }` を想定する。
- ソート中のカラムにはヘッダに▲/▼を付けて現在の方向を表示する。
- API から401が返るとログイン画面へリダイレクトする。
- 非管理者が UI で操作を試みた場合、フロント側で alert を出し実行を中止する。サーバ側でも API は管理者チェックで 403 を返す（安全対策）。

## 主な関数（一覧）
- `initServerManagement(initialQ)` - 初期化（DOM 準備、イベントバインド、初回検索）
- `doSearch(page)` - サーバ一覧を API から取得して描画
- `render(servers, total, page, size)` - テーブル描画処理
- `renderPagination(total, page, size)` - ページング UI
- `showContextMenu(ev)` - コンテキストメニュー表示（位置調整、ボタン状態制御）
- `hideContextMenu()` - メニュー非表示
- `disableSelectedServer()` - 選択サーバの無効化（POST 実行）
- `enableSelectedServer()` - 選択サーバの有効化（POST 実行）

## その他
- `#current-user-admin` 隠し要素から `data-is-admin` を読み取り、管理者フラグを得る実装になっているため、サーバ側のテンプレート出力がないと管理者判定ができない点に注意。

## 変更履歴
- 2026-01-09: 非管理者向けに「無効化/有効化」ボタンの視覚的無効化とフロントガードを追加（`data-is-admin` を参照）。
- 2026-01-09: 「後で追加」機能を UI と API から完全廃止（関連 JS と API case を削除）。
- 2026-01-16: 列ソート時にヘッダーへ▲/▼を表示するように変更。

## コミットメッセージ例
- docs(front): server_list.js の仕様書を追加（管理者UIガードと schedule_add 廃止を反映）
