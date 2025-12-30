# LogTransmitter

対象: `src/main/java/com/edamame/agent/network/LogTransmitter.java`

## 概要
- 収集済みログ (`LogEntry`) を Edamame サーバへ TCP で送信する責務を持つコンポーネント。接続管理（認証・再接続）、送信キュー、キュー耐久化（メモリキュー）、および送信処理の同期を担当する。

## 主な機能
- TCP 接続の確立・認証（`connectAndAuthenticate`, `authenticate`）
- 再接続モードの管理（リトライ、スケジュールされた再接続試行）
- 送信キュー（`logQueue`）管理とバッファリング
- ログ送信の内部処理（`transmitLogsInternal` など）とレスポンス処理
- 登録（registerServer） / 登録解除（unregisterServer） / heartbeat / block requests の送受信

## 挙動
- 接続が切断されると再接続モードに入り定期的に接続を試みる。接続成功時はキューに溜めたログを順次送信する。
- 送信前に `ensureConnection()` を呼び、接続確立・認証済みかを確認する。接続不可時はログをキューへ戻すか保存する。
- 再接続成功時のコールバックをサポーター（EdamameAgent）へ通知する API を持つ。

## 細かい指定された仕様
- キューは `ConcurrentLinkedQueue` を利用し、`MAX_QUEUE_SIZE` の上限を持つ。上限越え時は古いエントリを破棄して現在ログを受け入れる。
- 接続・送信処理は `ReentrantLock` で排他制御される。
- JSON シリアライズは Jackson を利用し、LocalDateTime 対応モジュールを登録している。
- プロトコル定数は `TcpProtocolConstants` を参照する（メッセージ型やレスポンスコード）。

## メソッド一覧と機能（主なもの）
- `public LogTransmitter(AgentConfig config)` - コンストラクタ
- `public synchronized boolean transmitLogs(List<LogEntry> logs)` - 送信エントリの受け皿（接続不可時はキューに蓄積）
- `private boolean ensureConnection()` - 接続と認証の確保
- `private boolean connectAndAuthenticate()` - 実際の接続／認証ハンドル
- 再接続管理関数（`startReconnectMode`, `attemptReconnect`, `stopReconnectMode`）
- キュー処理（`queueLogs`, `processQueuedData`, `processQueuedData` 内の registerServer 呼び出し等）
- `public void setReconnectionSuccessCallback(Runnable callback)` - 再接続成功時コールバック設定

## 変更履歴
- 2.4.0 - 2025-12-31: ドキュメント作成（実装に基づく）

## コミットメッセージ例
- docs(agent): LogTransmitter の仕様書を追加

