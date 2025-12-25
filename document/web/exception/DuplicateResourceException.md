# DuplicateResourceException（例外仕様）

## 概要
`DuplicateResourceException` は、リソースの一意制約違反（例：ユーザー名の重複）を表すためのカスタムランタイム例外です。`UserServiceImpl.createUser` などで DB の重複エラーを検出した際に発生させ、コントローラが HTTP 409 を返すために使用されます。

実装ファイル:
- `src/main/java/com/edamame/web/exception/DuplicateResourceException.java`

## 使用箇所
- `UserServiceImpl.createUser(...)` で username の重複を検出した場合にスロー

## コントローラでの扱い
- `UserManagementController.handleCreateUser` は `DuplicateResourceException` を捕捉し、HTTP 409 と本文 `{ "error": "username already exists" }` を返すように実装されています。

## UI 側ハンドリング
- クライアントは HTTP 409 を受け取った際、`user_modal.js` の `showModalErrorFromResponse` によって `#username-error` にインラインエラーを表示する。

## テストケース
- 既存の username を使って `POST /api/users` を行うと HTTP 409 とメッセージが返ること。


