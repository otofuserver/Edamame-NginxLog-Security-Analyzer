# AdminRetentionException（例外仕様）

## 概要
`AdminRetentionException` は、アプリケーションのユーザー管理において「最後の有効な `admin` アカウントを無効化・削除・ロール削除」しようとした場合にスローされるカスタム例外です。これはシステムが管理者不在の状態になることを防止するための保護機構です。

実装ファイル:
- `src/main/java/com/edamame/web/exception/AdminRetentionException.java`

## 発生箇所（呼び出し元）
- `UserServiceImpl.updateUser(...)` — `admin` を無効化しようとする際に、他に有効な `admin` が存在しない場合にスローされる。
- `UserServiceImpl.deleteUser(...)` — `admin` を削除しようとする際に、他に有効な `admin` が存在しない場合にスローされる。
- `UserServiceImpl.removeRoleFromUser(...)` — `admin` ロールを削除し、結果的に有効 `admin` がいなくなる場合にスローされる。

## コントローラでの扱い
- `UserManagementController` は `AdminRetentionException` をキャッチし、クライアントに HTTP 400 を返します。レスポンスは以下のような JSON 形式です:
```json
{ "error": "admin権限を持つアカウントが他に存在しません。最後のadminを削除できません。" }
```
（文言は操作の種類により若干変わることがあります）

## テストケース
- 単一の admin ユーザーのみが存在する DB で、当該 admin を無効化/削除/ロール削除しようとすると `AdminRetentionException` が発生し、コントローラは HTTP 400 を返すこと。
- 複数の admin が存在する場合は例外が発生せず、操作が成功すること。

## 設計上の注意点
- 本例外はサーバ側での最終防御であり、フロント側での UX 制御（編集不可の表示やクリック無効化）と併用しますが、フロントだけでは不十分です。サーバ側で強制的にチェックする必要があります。
- 並列実行（レースコンディション）を完全に防ぐにはトランザクションと行レベルロック（`SELECT ... FOR UPDATE`）を導入するとよいです。現状は短時間のチェックと例外による防御です。


