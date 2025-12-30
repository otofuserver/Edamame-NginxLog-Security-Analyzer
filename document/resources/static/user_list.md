# user_list.js (一覧描画・検索)

対象: `src/main/resources/static/user_list.js`

## 概要
- ユーザー一覧（検索・ページング・ソート）をクライアント側で扱うフロントエンドモジュール。外部に `window.UserList` API を公開する。

## 主な機能
- ユーザー一覧取得（doSearch）
- テーブル描画（render）とページネーション（renderPagination）
- 初期化処理（initUserManagement）で DOM 要素検出とイベント登録

## 挙動
- 検索文字列が空なら `/api/users?page=&size=` を呼び出す。検索語がある場合は `/api/users?q=&page=&size=20` を使用する。取得結果は JSON（{users, total, page, size}）を想定する。
- 行クリックで `UserModal.openUserModal(user)` を呼び出して詳細モーダルを表示する基本動作を備える。
- ソートはクライアント側で実行され、列ヘッダ（th[data-column]）をクリックで昇降切替する。

## 細かい指定された仕様
- デフォルトの page size は 20 件。大量データ時はサーバサイドソート/ページングを優先する設計。
- 検索入力はデバウンス（250ms）して API 呼び出しを制御する。
- DOM 要素が未作成のタイミングに対応するために再試行ロジック（最大リトライ）を実装している。

## その他
- 依存モジュール: `user_modal.js`, `script.js`（bootstrap）
- テスト: API のレスポンス形式が想定どおりであること（users 配列、total 整数）を確認する自動テストを推奨。

## 主な関数一覧
- doSearch(page)
- render(users, total, page, size)
- renderPagination(total, page, size)
- initUserManagement(initialQ)
- attachHeaderSortHandlers()

## 変更履歴
- 1.0.0 - 2025-12-29: ドキュメント整備

## コミットメッセージ例
- docs(web): user_list.js の仕様書を統一フォーマットへ変換
