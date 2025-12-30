# AppLogger

対象: `src/main/java/com/edamame/security/tools/AppLogger.java`

## 概要
- プロジェクト共通のログ出力ユーティリティ。標準出力にタイムスタンプ付きのログを出力するラッパークラス。
- 環境変数 `NGINX_LOG_DEBUG` により DEBUG レベルの出力を制御する。

## 主な機能
- レベル付きログ出力（INFO/WARN/ERROR/DEBUG/CRITICAL/RECOVERED）
- 共通フォーマット（`[yyyy-MM-dd HH:mm:ss][LEVEL] message`）での出力
- 簡易的な DEBUG フラグによる出力制御

## 挙動
- `log` メソッドでフォーマットして標準出力に出力する。DEBUG レベルは環境変数を確認し、`true` でない場合は出力しない。
- その他のレベルはすべて出力される。

## 細かい指定された仕様
- タイムスタンプフォーマットは `yyyy-MM-dd HH:mm:ss` を使用する。
- 出力先は現在 `System.out`（コンソール）であるが、将来的には SLF4J + Logback へ切り替えることが推奨される。
- ログメッセージには個人情報やパスワード等を含めないこと（セキュリティ運用ルール）。

## メソッド一覧と機能（主なもの）
- `public static void log(String msg, String level)`
  - 共通ログ出力メソッド。`level` が `DEBUG` の場合は環境変数 `NGINX_LOG_DEBUG` を確認する。

- `public static void info(String msg)`
  - INFO レベルで出力するユーティリティ。

- `public static void warn(String msg)`
  - WARN レベルで出力するユーティリティ。

- `public static void error(String msg)`
  - ERROR レベルで出力するユーティリティ。

- `public static void debug(String msg)`
  - DEBUG レベルで出力するユーティリティ（環境変数で制御）。

- `public static void critical(String msg)`
  - CRITICAL レベルで出力するユーティリティ。

- `public static void recovered(String msg)`
  - RECOVERED レベルで出力するユーティリティ。

## その他
- 本クラスは現在 `System.out` を使用しているため、運用環境では Logback 等への切り替えを検討すること。
- ログ出力のユニットテストを作成する際は、出力先を差し替えられるよう抽象化の検討を推奨する。

## 変更履歴
- 1.0.0 - 2025-12-30: ドキュメント作成

## コミットメッセージ例
- docs: AppLogger の仕様書を追加
