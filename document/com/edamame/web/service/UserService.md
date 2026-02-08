# UserService

対象: `src/main/java/com/edamame/web/service/UserService.java`

## 概要
- ユーザー管理に関するビジネスロジックのインターフェース定義。検索・取得・更新・削除・ロール付与・パスワード操作など、ユーザー関連操作の契約を規定する。

## 主な機能（契約）
- ユーザー検索（ページネーション対応）
- 単一ユーザー取得（username 指定）
- 管理者判定（isAdmin）
- ユーザー更新 / 削除
- ロール一覧取得・ユーザーへのロール付与/削除
- ユーザー作成・パスワードリセット・パスワード自動生成（次回ログインで変更強制フラグを扱う）
- ログイン履歴取得
- メール変更の所有者確認フローの起点・検証（request/verify）

## 挙動（実装側への期待）
- 実装は DB 操作で失敗した場合に適切な例外や false を返すこと。
- パスワード操作はハッシュ化ライブラリ（BCrypt 等）を用いて安全に実装し、`requireChangeNextLogin` フラグで初回変更必須を扱うこと。
- 管理者削除や無効化では最終管理者を残す保護（AdminRetentionException 等）を適用すること。
- メール変更フローは DB にワンタイムコードのハッシュを保存し、検証成功時に users.email を更新するトランザクションを提供すること。

## メソッド一覧（契約）
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
- `boolean resetPassword(String username, String plainPassword, boolean requireChangeNextLogin)`
- `String generateAndResetPassword(String username, boolean requireChangeNextLogin)`
- `List<Map<String,String>> getLoginHistory(String username, int limit)`
- `long requestEmailChange(String username, String newEmail, String requestIp)`
- `boolean verifyEmailChange(String username, long requestId, String code)`
- `boolean hasExpiredUnusedActivationToken(String username)`
- `boolean resendActivationEmail(String username)`
- `boolean hasRoleIncludingHigher(String username, String roleName)`

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成
- 1.1.0 - 2026-02-08: パスワード操作メソッドに `requireChangeNextLogin` を追加し仕様を更新

## コミットメッセージ例
- docs(web): UserService に email-change メソッドの契約を追加
