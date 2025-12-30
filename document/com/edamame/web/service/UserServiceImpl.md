# UserServiceImpl（サービス実装）

## 概要
`UserServiceImpl` はアプリケーションのユーザー関連ビジネスロジックを実装するサービスクラスです。データベースアクセスを直接行い、検索、取得、作成、更新、削除、ロール操作、パスワード関連の機能を提供します。

実装ファイル:
- `src/main/java/com/edamame/web/service/impl/UserServiceImpl.java`

## 提供機能（メソッド）
- `PageResult<UserDto> searchUsers(String q, int page, int size)`
  - `users` テーブルと `login_history` を参照してユーザー一覧と最終ログインを返す。
  - 検索ワードは username・email に対して LIKE 検索。
  - ページング対応（page, size）。
  - SQL 実行で最大 `MAX_RETRIES` 回のリトライを行う。

- `Optional<UserDto> findByUsername(String username)`
  - 指定 username の詳細を取得。存在しない場合は empty を返す。

- `boolean isAdmin(String username)`
  - `users_roles` / `roles` を結合して `admin` ロールの保有を判定する。

- `boolean updateUser(String username, String email, boolean enabled)`
  - メールアドレスと有効フラグの更新。
  - 重要保護: 対象が `admin` であり `enabled=false`（無効化）を行う場合、他に有効な `admin` が存在するかを確認。存在しない場合は `AdminRetentionException` をスローして更新を拒否する。

- `boolean deleteUser(String username)`
  - ユーザー削除。
  - 重要保護: 対象が `admin` の場合、他に有効な `admin` が存在するかを確認。存在しない場合は `AdminRetentionException` をスローして削除を拒否する。

- `List<String> listAllRoles()`
  - すべてのロール名を返却。

- `List<String> getRolesForUser(String username)`
  - 指定ユーザーのロール一覧を返却。

- `boolean addRoleToUser(String username, String role)`
  - `users_roles` テーブルにロールを付与。重複キーは冪等として true を返す。

- `boolean removeRoleFromUser(String username, String role)`
  - 指定ユーザーからロールを削除。
  - 重要保護: `admin` ロールの削除で最後の有効 `admin` を失う場合は `AdminRetentionException` をスローする。

- `boolean createUser(String username, String email, boolean enabled)`
  - 生成したランダムパスワードを BCrypt でハッシュして `users` に保存。
  - 重複ユーザー名は `DuplicateResourceException` を投げる（コントローラで 409 にマップ）。

- `boolean resetPassword(String username, String plainPassword)`
  - 指定パスワードで BCrypt ハッシュを保存。

- `String generateAndResetPassword(String username)`
  - サーバ側でパスワードを生成してハッシュ化して保存し、生成パスワード（平文）を返す。

## リトライ・エラーハンドリング
- すべての DB 操作は `MAX_RETRIES`（デフォルト 5）回のリトライを実施。
- 重複キー（Duplicate Key）等の判定はエラーメッセージ判定で実装。

## テストケース（推奨）
- 検索でページング・検索ワードが正しく機能するか
- createUser: 正常作成・重複 username の場合に `DuplicateResourceException` が発生するか
- updateUser/deleteUser/removeRoleFromUser: 最後の `admin` 保護が機能するか（例: 単一 admin 環境で操作を失敗させる）
- generateAndResetPassword: 生成されたパスワードでログインできるか（統合テスト）

## 関連ファイル
- `src/main/java/com/edamame/web/service/UserService.java`（インターフェース）
- `src/main/java/com/edamame/web/dto/UserDto.java`
- `src/main/java/com/edamame/web/exception/AdminRetentionException.java`
- `src/main/java/com/edamame/web/exception/DuplicateResourceException.java`


