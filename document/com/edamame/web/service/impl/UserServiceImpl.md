# UserServiceImpl

対象: `src/main/java/com/edamame/web/service/impl/UserServiceImpl.java`

## 概要
- `UserService` インターフェースの具体実装。ユーザー検索、取得、作成、更新、削除、ロール付与などユーザー管理に関する主要な操作を提供する。
- DB 操作は `DbService.getConnection()` 経由で行い、最大リトライ（MAX_RETRIES）を持つ堅牢な実装を行う。

## 主な機能
- ユーザー検索（ページネーション対応）
- ユーザー詳細取得
- 管理者判定（isAdmin）
- ユーザー作成 / 削除 / 更新
- ロール一覧取得・ユーザーへのロール追加/削除
- パスワードリセット・生成

## 挙動
- DB 操作で SQLException が発生した場合は最大 `MAX_RETRIES` 回リトライし、それでも失敗したらログ出力して失敗を返す。
- `createUser` はランダムな初期パスワードを生成して BCrypt でハッシュ化して保存し、重複時は DuplicateResourceException を投げる。
- 管理者削除・無効化時は `AdminRetentionException` により最終管理者を削除・無効化できないよう保護する。

## 細かい指定された仕様
- リトライは sleep(100 * attempt) を行いバックオフする。
- ページングは `PageResult<T>` を返し、`size` は最大 100 まで制限される。
- パスワードは at.favre.lib.crypto.bcrypt.BCrypt を用いてハッシュ化（cost 12 が使用されている箇所あり）。
- 例外は適切にログ出力し、必要に応じて上位へ例外を伝播する（重複等）。

## メソッド一覧と機能（主なもの）
- `public PageResult<UserDto> searchUsers(String q, int page, int size)`
- `public Optional<UserDto> findByUsername(String username)`
- `public boolean isAdmin(String username)`
- `public boolean updateUser(String username, String email, boolean enabled)`
- `public boolean deleteUser(String username)`
- `public List<String> listAllRoles()`
- `public List<String> getRolesForUser(String username)`
- `public boolean addRoleToUser(String username, String role)`
- `public boolean removeRoleFromUser(String username, String role)`
- `public boolean createUser(String username, String email, boolean enabled)`
- `public boolean resetPassword(String username, String plainPassword)`
- `public String generateAndResetPassword(String username)`

## エラーハンドリング
- 重複リソース（ユーザー名等）は DuplicateResourceException をスローすることで呼び出し側で適切に HTTP 409 等へ変換可能。
- 最後の管理者保護は AdminRetentionException をスローする。

## 注意事項
- ユーザー作成時の初期パスワードは安全に配布する必要がある（ログへは平文を出力しない）。
- 高頻度のユーザー操作がある場合はトランザクション設計とインデックスの最適化を検討する。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（実装に基づく）

## コミットメッセージ例
- docs(web): UserServiceImpl の仕様書を追加

