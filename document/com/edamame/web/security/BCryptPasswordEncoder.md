# BCryptPasswordEncoder

対象: `src/main/java/com/edamame/web/security/BCryptPasswordEncoder.java`

## 概要
- BCrypt を用いたパスワードハッシュ化ユーティリティクラス。外部ライブラリ at.favre.lib.crypto.bcrypt.BCrypt を内部で利用し、ハッシュ化と照合を提供する。

## 主な機能
- パスワードのハッシュ化（`encode`）
- 平文パスワードとハッシュの照合（`matches`）

## 挙動
- コンストラクタでストレングス（コストファクタ）を指定可能。デフォルトは 10。
- ストレングスは 4〜31 の範囲で指定し、範囲外は IllegalArgumentException を投げる。
- `encode` は与えた文字列を BCrypt ハッシュに変換して返す。`null` 受け取りは IllegalArgumentException を投げる。
- `matches` は安全に照合を行い、例外発生時は false を返すフェールセーフを採用している。

## 細かい指定された仕様
- ハッシュアルゴリズムは BCrypt（ライブラリ: at.favre.lib.crypto.bcrypt）を使用する。
- ハッシュ化強度（cost）は環境に応じて調整可能だが、パフォーマンスへの影響を考慮すること（高い cost は遅延を招く）。
- ハッシュ比較はタイミング攻撃の軽減を図るライブラリの実装に依存する。

## メソッド一覧と機能（主なもの）
- `public BCryptPasswordEncoder()` - デフォルトコンストラクタ（strength = 10）。
- `public BCryptPasswordEncoder(int strength)` - strength を指定するコンストラクタ（4〜31 の範囲）。
- `public String encode(CharSequence rawPassword)` - 平文パスワードをハッシュ化して返す。
- `public boolean matches(CharSequence rawPassword, String encodedPassword)` - 平文とハッシュの照合。

## その他
- パスワードの生成・リセット処理やパスワードの最小要件（長さ、文字種）ポリシーは本クラスの外部で決めること。ログに平文パスワードを出力してはならない。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成

## コミットメッセージ例
- docs(web): BCryptPasswordEncoder の仕様書を追加

