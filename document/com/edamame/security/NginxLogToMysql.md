# NginxLogToMysql

対象: `src/main/java/com/edamame/security/NginxLogToMysql.java`

## 概要
- NGINX ログの監視・解析と MySQL への保存を統括するメインクラス群のエントリポイント。DB 接続初期化、ModSecurity キュー初期化、ActionEngine/AgentTcpServer/Web フロントエンド等の初期化やライフサイクル管理を行う。

## 主な機能
- 暗号化された DB 設定の復号と DB 接続（AES-GCM・BouncyCastle）
- DbService の初期化とスキーマ同期
- ModSecurity キューの初期化・タスクスケジューリング
- Agent TCP サーバや Web フロントエンドの初期起動
- 初期化失敗時の安全な終了処理（ログ出力）

## 挙動
- 実行時に `SECURE_CONFIG_PATH` と `KEY_PATH` を使い暗号化設定���復号して DB 接続文字列と認証情報を取得する。
- DbService を初期化し、ModSecurity や ActionEngine、AgentTcpServer、ScheduledReportManager、WebApplication などを初期化して運用開始する。
- 起動後は定期的に攻撃パターンの自動更新やログクリーンアップを行うジョブを走らせる。
- シャットダウンでは関連コンポーネントを逐次クリーンアップする。

## 細かい指定された仕様
- AES-GCM 復号は先頭 12 バイトを nonce として取り出す実装。
- DB 接続 URL は UTF-8・serverTimezone=Asia/Tokyo 等のパラメータを付与して構築する。
- 初期化フェーズで重大エラーが発生した場合は起動を中止してプロセスを終了する。

## メソッド一覧と機能（主なもの）
- `private static Map<String,String> loadDbConfig()` - 暗号化ファイルから DB 設定を復号して Map で返す
- `private static boolean initializeApplication()` - アプリ全体の初期化処理
- `private static boolean initializeWebFrontend()` - Web フロントエンド初期化
- 初期化後のクリーンアップ/シャットダウン処理等多数

## 変更履歴
- 1.1.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(security): NginxLogToMysql の仕様書を追加

