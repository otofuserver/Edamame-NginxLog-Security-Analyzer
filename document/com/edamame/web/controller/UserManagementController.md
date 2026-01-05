# UserManagementController

対象: `src/main/java/com/edamame/web/controller/UserManagementController.java`

## 概要
- ユーザー管理関連の API とフラグメント提供を担う HTTP ハンドラ。ログイン済みの管理者ユーザー向けにユーザー一覧・作成・編集・削除、ロール管理、パスワードリセット等の操作を提供する。

## 主な機能
- フラグメント（`/api/fragment/users`）提供
- ユーザー一覧 API（`/api/users`）
- ユーザー詳細取得（`/api/users/{username}`）
- ユーザー作成（`POST /api/users`）
- ユーザー更新（`PUT /api/users/{username}`）
- ユーザー削除（`DELETE /api/users/{username}`）
- ロール付与/削除（`POST /api/users/{username}/roles`, `DELETE /api/users/{username}/roles/{role}`）
- パスワードリセット（`POST /api/users/{username}/reset-password`）
- 自分自身のプロファイル取得/更新（`GET/PUT /api/me/profile`）
- 自分のメール変更の所有者確認フロー（`POST /api/me/email-change/request`, `POST /api/me/email-change/verify`）

## 挙動
- リクエストごとに Cookie からセッションを検証し、認証されていない場合は 401 を返す。
- 管理者ロール（`admin`）の判定は `UserService.isAdmin` を使用し、管理者のみアクセス可能なエンドポイントは 403 を返す。
- パスやメソッドに応じてルーティングを行い、各ハンドラメソッド（`handleUsersApi`, `handleCreateUser`, `handleUpdateUser` など）へ振り分ける。
- JSON 入出力は Jackson (`ObjectMapper`) を使用し、LocalDateTime のサポートを有効にしている。
- 管理者保護（最後の admin を削除/無効化しない）を `AdminRetentionException` により実施する。

## 細かい指定された仕様
- 入力は `WebSecurityUtils.sanitizeInput` でサニタイズし、XSS 攻撃を軽減する。
- `handleUsersApi` はページネーション（`page`, `size`）と検索クエリ `q` をサポートする。
- `handleResetPassword` は空ボディの場合サーバ側でパスワードを生成して返却し、明示的なパスワードは要件チェック (`isValidPassword`) を満たす必要がある。
- エラー応答は JSON で返却する（`sendJsonError` / `sendJsonResponse`）。

### メール変更所有者確認フロー（/api/me/email-change/*）
- 目的: ユーザーがアカウントに登録されたメールアドレスを変更する際に、その新しいメールアドレスの所有者であることを確認するためのワンタイムコード検証フローを提供する。
- フロー概要:
  1. ユーザーが `PUT /api/me/profile` などからメールを変更要求する（クライアントは PUT を投げる）。
  2. サーバ側は `email_change_requests` テーブルにリクエストを作成し、6桁の確認コードを生成して新しいメール宛に送信する。`code_hash` を保存し、`expires_at` を設定する。
  3. クライアントは `POST /api/me/email-change/verify` に `{ requestId, code }` を送信する。
  4. サーバはハッシュ比較と有効期限・未使用チェックを行い、成功時に `users.email` を更新して `is_used` を true にする。
- セキュリティ/運用注意:
  - 確認コードは DB に平文で保存してはならない。ハッシュのみ保存すること。
  - 検証試行回数（attempts）に基づくロック/無効化を実装してブルートフォースを防ぐこと（推奨: 5回）。
  - メール送信ログ・監査を残すこと。

## メソッド一覧と機能（主なもの）
- `public void handle(HttpExchange exchange)` - エントリポイント、認証確認とルーティング。
- `private void handleFragment(HttpExchange exchange)` - フラグメント HTML を返す。
- `private void handleUsersApi(HttpExchange exchange)` - ユーザー一覧 API 処理。
- `private void handleCreateUser(HttpExchange exchange)` - ユーザー作成処理。
- `private void handleUpdateUser(HttpExchange exchange)` - ユーザー更新。
- `private void handleDeleteUser(HttpExchange exchange)` - ユーザー削除。
- `private void handleGetUserDetail(HttpExchange exchange)` - ユーザー詳細取得。
- `private void handleAddRole(HttpExchange exchange)` - ロール付与。
- `private void handleRemoveRole(HttpExchange exchange)` - ロール削除。
- `private void handleResetPassword(HttpExchange exchange)` - パスワードリセット。
- `private void handleChangeMyPassword(HttpExchange exchange)` - 自身のパスワード変更。
- `private void handleRequestEmailChange(HttpExchange exchange)` - メール変更リクエスト作成（`POST /api/me/email-change/request`相当）。
- `private void handleVerifyEmailChange(HttpExchange exchange)` - 確認コード検証（`POST /api/me/email-change/verify`相当）。
- 各ユーティリティメソッド（`parseQueryParams`, `parseFormData`, `sendJsonResponse`, `sendJsonError`, `getAuthenticatedUsername` 等）

## セキュリティ/運用
- 管理者操作はすべて `isAdmin` 確認を行う。
- パスワードの取り扱いは厳格にし、ログに平文を出力しないこと。
- 大量削除や集計の高負荷 API には適切なレート制御を検討すること。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（実装に基づく）
- 2026-01-05: メール変更の所有者確認フロー（`email_change_requests` / `POST /api/me/email-change/*`）を追加

## コミットメッセージ例
- docs(web): UserManagementController の仕様書を追加/更新
