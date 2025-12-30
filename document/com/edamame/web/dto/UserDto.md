# UserDto

対象: `src/main/java/com/edamame/web/dto/UserDto.java`

## 概要
- Web 層と JSON シリアライズ間のデータ伝達を担う DTO。Jackson による serialization/deserialization を想定し、デフォルトコンストラクタと getter/setter を提供する。

## フィールド
- `Long id`
- `String username`
- `String email`
- `LocalDateTime lastLogin`
- `boolean enabled`

## 挙動
- Jackson 互換のためデフォルトコンストラクタ（引数なし）を実装している。フィールドの null 安全性は呼び出し側で担保する。

## メソッド一覧
- デフォルトコンストラクタ / オーバーロードコンストラクタ
- getter/setter 一式（`getId`, `setId`, `getUsername`, `setUsername`, ...）

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(web): UserDto の仕様書を追加

