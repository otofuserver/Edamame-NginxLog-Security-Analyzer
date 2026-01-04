# NginxLogToMysql

対象: `src/main/java/com/edamame/security/NginxLogToMysql.java`

## 概要
- NGINXログの監視・解析とMySQL保存を統括するメインアプリケーションクラス。DB接続初期化、ModSecurityキューと関連タスクの初期化、ActionEngine / AgentTcpServer / Webフロントエンドの起動・管理、定期ジョブのスケジューリング、シャットダウン処理を提供する。

## 主な機能
- AES-GCM（BouncyCastle）で暗号化されたDB設定ファイルを復号してDB接続設定を取得
- `DbService` の初期化と `DbSchema` によるスキーマ同期
- ModSecurityキューの初期化とスケジューリングされたタスク管理
- ActionEngine と共有 `MailActionHandler` の初期化
- Agent TCP サーバーの起動と定期ジョブ（攻撃パターン更新、ログクリーンアップ）のスケジューリング
- Webフロントエンド（`WebApplication`）の初期化と監視

## 挙動
- 起動時、`SECURE_CONFIG_PATH` と `KEY_PATH` を使って暗号化されたDB設定を復号する。復号に失敗すると初期化は中止される。
- `DbService.initialize(...)` を呼びDB接続を確立、`syncAllTablesSchema()` を実行してDBスキーマを自動整合する。
- ModSecurityキュー、ActionEngine、AgentTcpServer、ScheduledReportManager を順次初期化する。Webフロントエンドは環境変数で有効/無効を切替可能。
- 定期的に攻撃パターンの自動更新をチェックし、ログクリーンアップバッチを実行する。
- シャットダウン時は各コンポーネントを順次停止し、DB接続をクリーンに終了する。

## 細かい指定された仕様
- 暗号化ファイルの復号は先頭12バイトをnonceとして扱う（AES-GCMの仕様）。
- DB接続URLには`useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Tokyo&characterEncoding=UTF-8&useUnicode=true`などのパラメータを付与する。
- 最大再試行回数やリトライディレイは環境変数 `MAX_RETRIES` / `RETRY_DELAY` で制御可能。
- 共有の `MailActionHandler` を1インスタンス生成して`ActionEngine`等で使い回す設計。

## その他
- 環境変数による挙動変更点: `ENABLE_WEB_FRONTEND`, `WEB_PORT`, `ATTACK_PATTERNS_PATH` 等がある。
- 初期化処理中に致命的なエラーが発生した場合は起動を中止しログにCRITICALを出力する。

## 変更履歴
- 2026-01-05: ドキュメント更新（自動整形）
- 2025-12-31: v1.1.0 ドキュメント作成

## メソッド一覧と機能（主なもの）
- `private static String getEnvOrDefault(String envVar, String defaultValue)` - 環境変数を取得し、未設定時はデフォルトを返す。
- `private static Map<String,String> loadDbConfig()` - 暗号化ファイルをAES-GCMで復号し、DB接続情報をMapで返す（例外発生時はthrow）。
- `private static boolean initializeApplication()` - アプリケーション全体の初期化（DbService初期化・ModSecurityキュー・ActionEngine等の初期化）。
- `private static boolean initializeWebFrontend()` - Webフロントエンド（`WebApplication`）の初期化・監視スレッド設定。
- `private static void cleanup()` - アプリケーション停止時のクリーンアップ処理（各コンポーネント停止、DbService shutdown）。
- `public static void main(String[] args)` - アプリケーションのエントリポイント。
