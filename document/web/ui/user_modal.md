# user_modal.js（モーダル実装仕様）

ファイル: `src/main/resources/static/user_modal.js`

## 概要
ユーザー編集・作成用モーダルの表示制御、保存・削除・パスワード生成・ロール編集の UI ロジックを実装しています。サーバAPIとの通信（fetch）を通してデータ取得・更新を行い、エラーメッセージを適切にインライン / モーダル領域に表示します。

## 主な機能
- `openUserModal(user)` / `openCreateUserModal()`
  - `openUserModal` はモーダル表示前に `/api/users/{username}` を fetch して詳細を取得し、成功したらモーダルを表示する（403 応答時はモーダルを表示せずエラーを表示）。これにより自己編集で一瞬表示される問題を回避。
- `saveUser()`
  - 作成/更新の投稿処理を実行。
  - 作成時は POST `/api/users` → 成功後に保留中ロールを付与
  - 更新時は PUT `/api/users/{username}` → 成功後に保留中のロール追加/削除を適用
  - 409 の場合は username のインラインエラーを表示
  - create コンテキストで 500 の場合は汎用メッセージを表示
- `deleteUser()`
  - DELETE `/api/users/{username}` 実行。失敗時は `showModalErrorFromResponse` を使ってモーダル内エラーメッセージを表示
- `onGeneratePassword()` / `onCopyPassword()`
  - `onGeneratePassword()` はサーバPOSTで生成（空ボディ）し、返却された生成パスワードを一度だけ表示する
  - `onCopyPassword()` は `navigator.clipboard` を優先利用、失敗時は fallback で textarea コピーを試行
- ロール編集は `_pendingAdds` / `_pendingRemovals` に保持し、保存時に纏めて API を呼ぶ

## エラーハンドリング
- `showModalErrorFromResponse(resp, context)` により、409 は username用のインラインメッセージへ、create+500 は汎用文言へ、その他はモーダル上部に表示。

## UI要素（ID）
- `#user-modal`（モーダル root）
- `#modal-id`, `#modal-username`, `#modal-email`, `#modal-enabled`（入力フィールド）
- `#modal-save`, `#modal-delete`, `#modal-cancel`（操作ボタン）
- `#role-select`, `#role-add`, `#user-roles`（ロール関連UI）
- `#generated-password`, `#password-copy`, `#password-generate`（パスワード生成UI）
- `#modal-error`, `#username-error`（エラー表示領域）

## テストケース（推奨）
- openUserModal で自分の username を指定し 403 が返るケースでモーダルが表示されないこと
- createUser で重複 username を送ったときに `#username-error` にメッセージが表示されること
- パスワード生成でサーバの生成値が表示され、コピーが成功すること
- ロール追加/削除を行い保存で API 呼び出しが正しく行われること


