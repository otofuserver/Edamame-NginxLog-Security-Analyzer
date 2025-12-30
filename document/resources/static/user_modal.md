# user_modal (ユーザーモーダル)

対象: `src/main/resources/static/user_modal.js` および関連 HTML 断片

## 概要
- ユーザーの作成・編集・削除を行うモーダル UI のフロントエンドロジック。パスワード生成・コピー、ロール編集、エラーメッセージ表示などの操作を提供する。

## 主な機能
- モーダル表示 / 作成モード / 編集モードの切替（openCreateUserModal / openUserModal）
- ユーザー保存（saveUser）／削除（deleteUser）／パスワード生成（onGeneratePassword）／コピー（onCopyPassword）
- ロールの追加・削除のステージング（stageAddRole / stageRemoveRole）

## 挙動
- 作成モードでは username 入力を必須とし、作成後に保留中のロール追加を順次適用する。
- 編集モードでは既存ユーザー情報を取得してフォームへ反映し、ロールの追加・削除は差分で API を呼び出す。
- パスワード生成はサーバ API を呼び出して一時的に表示し、コピー操作はクリップボード API を優先して試み、フォールバックで textarea を使用する。

## 細かい指定された仕様
- DOM 要素は id で取得（#modal-id, #modal-username, #modal-email, #modal-enabled, #password-generate, #password-copy 等）。
- パスワード生成はサーバ側で行い、UI 上では一度だけ表示する（再表示不可）という運用仕様を守る。
- エラー表示は `#modal-error`（汎用）と `#username-error`（重複など入力エラー）に分けて行う。

## その他
- セキュリティ: 生成パスワードは UI からは永続化しない。運用ではパスワードは必ず個別に保管・伝達すること。
- UX: ロール追加/削除は UI 上でステージング表示し、保存時にバッチで適用することで API 呼び出し数を最小化する。

## 主な関数一覧
- openUserModal(user)
- openCreateUserModal()
- saveUser()
- deleteUser()
- onGeneratePassword()
- onCopyPassword()
- attachModalEventHandlers()

## 変更履歴
- 1.0.0 - 2025-12-29: ドキュメント作成

## コミットメッセージ例
- docs(web): user_modal.js の仕様書を統一フォーマットへ変換
