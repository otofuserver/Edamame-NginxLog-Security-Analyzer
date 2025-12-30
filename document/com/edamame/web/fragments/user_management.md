# user_management.html（断片テンプレート仕様）

対象: `src/main/resources/fragments/user_management.html`

(このファイルは `document/web/fragments/user_management.md` から移動されました)

## 概要
管理画面の "ユーザー管理" セクションを構成する HTML 断片テンプレートです。フロント側で読み込み（`/api/fragment/users`）され、ユーザー検索、一覧表示、作成ボタン、ページング領域、モーダル領域を含みます。

## 主な要素（想定）
- 検索フォーム（`#user-search-form`）と入力 `#q`
- ユーザー一覧テーブル（`#user-results-body`）
- ページング領域（`#pagination`）
- ユーザー作成モーダル（`#user-modal`）の HTML 構造

## 動作
- クライアントはこの断片を取得して DOM に挿入後、`UserList.initUserManagement()` を呼んで検索・一覧の初期化を行う。

## テスト/チェックポイント
- テンプレートに ID 属性が存在するか（`#user-results-body`, `#pagination`, `#user-search-form`, `#user-modal`）
- モーダルの要素が `user_modal.js` の参照 ID と一致しているか（例: `modal-username` 等）

## 関連
- `UserManagementController.handleFragment()`（断片提供）
- `src/main/resources/static/user_list.js`, `src/main/resources/static/user_modal.js`

