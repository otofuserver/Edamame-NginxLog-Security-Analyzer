# SetupSecureConfig

対象: `src/main/java/com/edamame/tools/SetupSecureConfig.java`

## 概要
- データベース接続情報を対話的に入力または環��変数から取得し、AES-GCM（BouncyCastle）で暗号化してファイルに保存するセットアップツール。
- 秘匿キー（secret.key）を生成してローカルに保存し、暗号化された構成ファイル（db_config.enc）を作成するワークフローを提供する。

## 主な機能
- AES-256 キーの生成とファイル保存（`generateKey`）
- ユーザー入力または環境変数から DB 接続情報を収集（`createConfig` / `getEnvOrInput`）
- AES-GCM による暗号化と保存（`encryptAndStoreConfig`）
- ファイルの権限設定（Unix 系で 600 相当）（`setFilePermissions`）
- CLI ベースの進捗メッセージ出力（標準出力）

## 挙動
- 実行時にキーを生成して `./secret.key` に保存する。次に DB 情報を JSON 化し、AES-GCM で暗号化して `./db_config.enc` に保存する。
- 暗号化時は 12 バイトの nonce（IV）を先頭に出力し、その後に暗号文（GCM 出力）を書き込む実装。
- 権限設定は Unix 系のみ試行し、Windows ではスキップする。
- 対話入力ではパスワード入力があればコンソールの `readPassword` を使い隠蔽する。コンソールがない場合は標準入力を使用する。

## 細かい指定された仕様
- AES-GCM のタグ長は 128 ビットで設定される。
- キー長は 32 バイト（256 ビット）。nonce は 12 バイトを使用する。
- ファイル保存に失敗した場合は例外を投げて終了する。最終的に標準出力に完了メッセージを表示する。
- 環境変数優先：`TEST_MYSQL_HOST` 等が設定されていれば対話はスキップして環境変数を利用する。

## メソッド一覧と機能（主なもの）
- `private static byte[] generateKey()` - AES-256 キーを生成してファイルへ保存する。
- `private static String createConfig()` - 環境変数または対話入力から DB 設定を取得して JSON 文字列を返す。
- `private static void encryptAndStoreConfig(byte[] key, String data)` - AES-GCM で暗号化してファイルに保存する。
- `private static String getEnvOrInput(String envVar, String prompt, boolean isPassword)` - 環境変数または CLI 入力を取得。
- `private static void setFilePermissions(Path path)` - Unix 系でファイル権限 600 を設定（可能な場合）。
- `public static void main(String[] args)` - ツールのエントリポイント。

## エラーハンドリング
- I/O エラーや暗号処理エラーはキャッチされメッセージを表示した後プロセスを終了する。scanner は finally でクローズする。

## 変更履歴
- 1.1 - 2025-12-30: ドキュメント作成（実装に基づく）

## コミットメッセージ例
- docs(tools): SetupSecureConfig の仕様書を追加

