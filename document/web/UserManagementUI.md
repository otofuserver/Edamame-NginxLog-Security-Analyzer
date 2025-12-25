# User Management UI（ユーザー管理フロント仕様）

## 概要
管理者向けのユーザー管理 UI に関する実装・挙動仕様をまとめます。主に `src/main/resources/fragments/user_management.html` と `src/main/resources/static/user_list.js`, `src/main/resources/static/user_modal.js` に実装されています。今回の改修では以下を実装・改善しました：

- ユーザー検索がインラインでフィルタされる（Search 入力で即時フィルタ、ページングあり）
- ユーザー一覧はテーブル表示（username, email, lastLogin, enabled）
- 行をクリックで編集モーダルを開く（ただし自分自身の行は編集不可にする UX 保護）
- モーダルは表示前に /api/users/{username} で詳細を取得し、成功時にのみ表示（403 時に一瞬表示される問題を解消）
- ユーザー作成時の重複 username は 409 を受けて username 欄直下にインラインエラーを表示
- パスワード生成はサーバ側で一度だけ生成し、フロントで一度だけ表示（コピー機能あり）
- 削除・更新・ロール変更時はモーダルの上部にエラーメッセージ領域を表示

## 主要ファイル
- `src/main/resources/static/user_list.js` - 検索、一覧描画、ページング、作成ボタンの挿入
- `src/main/resources/static/user_modal.js` - モーダル表示制御、保存/削除/パスワード生成、エラーハンドリング

## 重要な UI 挙動（仕様）

1. 行クリックと自己編集禁止
   - 通常: 行クリックで `UserModal.openUserModal(user)` を呼ぶ。
   - 自己行: サーバ側の自己編集禁止チェックに加え、UX向上のため将来的にクライアント側で自己行のクリックを無効化して行のスタイルを薄くする（別途実装）。
   - 既存実装: 現在はサーバ側が 403 を返し、`user_modal.js` が fetch の結果を見てモーダルを表示しないようにしているため、一瞬表示される不具合は解消済み。

2. モーダル表示のフロー
   - `openUserModal(user)`:
     1. モーダルの初期フィールドにユーザー名等を設定
     2. `/api/users/{username}` を fetch して詳細（roles, allRoles 等）を取得
     3. 成功時にのみモーダルを表示し、ロール選択肢を populate、イベントハンドラを attach
     4. エラー時は `showModalErrorFromResponse` を利用してモーダル上部エリアにエラーメッセージを表示し、モーダルは表示しない

3. エラーメッセージ表示ポリシー
   - HTTP 409（重複 username）: `#username-error` にインラインで日本語の案内を表示（"ユーザー名は既に使用されています。別の名前を指定してください。"）
   - それ以外のエラー: `#modal-error`（モーダル上部）にメッセージを表示
   - ユーザー作成時の 500 はクライアントでは汎用メッセージ（"ユーザー作成に失敗しました。"）を表示

4. パスワード生成 / リセット
   - フロントから `POST /api/users/{username}/reset-password` を送信（ボディ空）すると、サーバ側でランダムパスワードを生成して保存し、生成パスワードをレスポンスに返す。
   - フロントは一時的に生成パスワードを表示し、コピーボタンによりクリップボードにコピー可能。
   - 生成済パスワードは一度限りの表示であり、モーダルを閉じると消去される。

5. ロール編集の適用
   - モーダル内でロールの "追加" を行うと UI 上の待機リスト（_pendingAdds）に追加され、"保存" を押すとまとめて API へ適用する。
   - 削除は同様に _pendingRemovals に保管し、保存時に DELETE API を叩く
   - サーバ側で "最後の admin 保護" が働くと保存時に HTTP 400 が返り、モーダル上部に説明が表示される

## 推奨 UI テストケース
- 検索入力が空の状態でユーザー一覧がページングされて表示される
- 検索ワード入力で即時フィルタリングされる（Enter不要）
- 自分の行をクリックしてもモーダルは表示されない（403 応答がモーダル表示を阻止）
- 他ユーザーの行をクリックするとモーダルが表示される
- ユーザー作成で重複 username を送ると username 欄の下にインラインエラーが表示される
- パスワード生成を行うとサーバで生成された新パスワードが一度だけ表示され、コピー可能

## 今後の改善案（UI）
- クライアント側で自分の行をクリック不可にして、視覚的に編集不可を示す（薄い色、編集アイコンを無効化）
- ロール追加/削除操作を undo 可能にする（UI 側で変更履歴を持つ）
- エラー時に field-level validation を増やす（email フォーマット、username 文字種チェック等）

## 参照
- `document/web/controller/UserManagementController.md` (サーバAPI) 
- 実装: `src/main/resources/static/user_modal.js`, `src/main/resources/static/user_list.js`


