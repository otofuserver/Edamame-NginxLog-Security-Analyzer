# UserDto

ファイル: `src/main/java/com/edamame/web/dto/UserDto.java`

## 概要
`UserDto` はユーザー情報の転送オブジェクトで、フロントとサーバ間でユーザーの基本情報をやり取りするために使用されます。Jackson によるシリアライズ/デシリアライズを想定し、デフォルトコンストラクタ / getter / setter を実装することが要求されます。

## 主なフィールド（例）
- `Long id` – ユーザーID
- `String username` – ユーザー名
- `String email` – メールアドレス
- `LocalDateTime lastLogin` – 最終ログイン時刻（`login_history` から参照）
- `boolean enabled` – アカウント有効フラグ

## 重要仕様
- Jackson の互換性のため、デフォルトコンストラクタおよび getter/setter を必ず保持すること（静的解析で未使用警告が出ても削除しない）。
- `lastLogin` は `java.time.LocalDateTime` 型を利用するため、コントローラ側で `jackson-datatype-jsr310` を登録していること（`ObjectMapper.registerModule(new JavaTimeModule())`）を確認する。

## テストケース
- `UserDto` を JSON にシリアライズ/デシリアライズして、`lastLogin` が正しく round-trip されること。

## 関連
- `UserServiceImpl.searchUsers()`（`UserDto` の生成箇所）
- `UserManagementController`（API レスポンス）

