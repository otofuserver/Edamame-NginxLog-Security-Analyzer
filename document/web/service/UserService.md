# UserService（インターフェース仕様）

ファイル: `src/main/java/com/edamame/web/service/UserService.java`

## 概要
`UserService` はユーザー管理に関するビジネスロジックのインターフェースです。`UserServiceImpl` がその具象実装を提供します。

## 提供メソッド（仕様）
- `PageResult<UserDto> searchUsers(String q, int page, int size)`
- `Optional<UserDto> findByUsername(String username)`
- `boolean isAdmin(String username)`
- `boolean updateUser(String username, String email, boolean enabled)`
- `boolean deleteUser(String username)`
- `List<String> listAllRoles()`
- `List<String> getRolesForUser(String username)`
- `boolean addRoleToUser(String username, String role)`
- `boolean removeRoleFromUser(String username, String role)`
- `boolean createUser(String username, String email, boolean enabled)`
- `boolean resetPassword(String username, String plainPassword)`
- `String generateAndResetPassword(String username)`

## 仕様上の注意点
- 例外の取り扱い: 重複リソースは `DuplicateResourceException`、最後の admin 保持違反は `AdminRetentionException` を用いる。
- DB へのアクセスはリトライポリシー（最大 `MAX_RETRIES`）を持つ旨を実装に期待する。

## 関連実装
- `src/main/java/com/edamame/web/service/impl/UserServiceImpl.java`


