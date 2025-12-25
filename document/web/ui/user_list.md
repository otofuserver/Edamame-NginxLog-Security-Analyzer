# user_list.js（一覧描画・検索）

ファイル: `src/main/resources/static/user_list.js`

## 概要
ユーザー検索入力のデバウンス処理、一覧の取得・描画、ページング、ソート、ユーザー作成ボタンの挿入を行うモジュールです。`window.UserList` として外部に API を公開しています。

## 主な機能
- `doSearch(page)`：`/api/users` を呼び出して一覧を取得し、`render()` に渡す
- `render(users, total, page, size)`：テーブルに行をレンダリング。行クリックで `UserModal.openUserModal(user)` を呼ぶ
- `renderPagination(total, page, size)`：前へ/次へボタンとページ情報の描画
- `initUserManagement(initialQ)`：初期化処理（DOM要素の存在確認・作成ボタン挿入・イベント割当て）
- 検索はインライン（入力時デバウンス）で即時フィルタ。Enter キーは submit を防止して検索を実行

## 重要挙動
- 検索文字列が空の場合は `GET /api/users?page=...&size=...` を呼ぶ（全件取得のページング）
- 検索がある場合は `GET /api/users?q=...&page=...&size=20` を呼ぶ
- テーブル行はクリックで編集モーダルを開く（ただしサーバ側の自己編集禁止により実際には開かれない場合がある）

## 今後の改善（推奨）
- クライアント側で `GET /api/session` などを用いて現在ログイン中のユーザー名を取得し、自分の行のクリックを非活性化することで UX を改善
- テーブルに「編集」ボタン列を追加し、行クリックと編集ボタンで異なる挙動を提供する

## テストケース
- 空検索で 20 件ずつページングされること
- 検索ワードでフィルタが効くこと
- ソートヘッダをクリックするとソートが切り替わること


