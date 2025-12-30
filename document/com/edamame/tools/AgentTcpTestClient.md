# AgentTcpTestClient

対象: `src/main/java/com/edamame/tools/AgentTcpTestClient.java`

## 概要
- Agent と TCP プロトコルで通信するサーバ（`AgentTcpServer`）向けのテストクライアント。接続、認証、ログ送信、ハートビート、ブロック要求の取得、サーバ登録/解除などのテストを行うユーティリティ。

## 主な機能
- TCP サーバへの接続/切断（`connect`, `disconnect`）
- 認証テスト（`testAuthentication`）
- サーバ登録/解除テスト（`testServerRegistration`, `testServerUnregistration`）
- ログバッチ送信テスト（`testLogBatch`）
- ハートビート送信テスト（`testHeartbeat`）
- ブロック要求取得テスト（`testBlockRequest`）

## 挙動
- 接続は `Socket` を用いて行い、デフォルトで `localhost:2591` に接続する。送受信は `DataInputStream` / `DataOutputStream` を利用し、メッセージはバイナリプロトコル（type byte + length int + payload）で送受信する。
- 認証メッセージは API キーとエージェント名を長さ付きバイナリで送信する。
- レスポンスは最初に 1 バイトのステータスコードを受け取り、必要に応じて 4 バイトの長さとレスポンス本体を読む。

## 細かい指定された仕様
- メッセージタイプは定義済みの定数（`MSG_TYPE_*`）を使用する。
- レスポンスコードは `RESPONSE_SUCCESS=0x00`、`RESPONSE_AUTH_FAILED=0x02` 等が定義されている。
- JSON 変換には Jackson の `ObjectMapper` を使用する。
- タイムアウトや例外はログに出力してテスト失敗として扱う。

## メソッド一覧と機能（主なもの）
- `public boolean connect()` - サーバへ TCP 接続を確立する。
- `public void disconnect()` - 接続をクローズする。
- `public boolean testAuthentication()` - 認証テストを行いレスポンスを確認する。
- `public boolean testServerRegistration()` - サーバ登録テストを行う。
- `public boolean testHeartbeat()` - ハートビート送信テストを行う。
- `public boolean testLogBatch()` - ログバッチ送信テストを実行する。
- `public boolean testBlockRequest()` - ブロック要求取得テストを実行する。
- `private void sendAuthMessage()` - 認証メッセージを構築・送信するユーティリティ。
- `private void sendMessage(byte messageType, byte[] data)` - 共通の送信ユーティリティ。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（実装に基づく）

## コミットメッセージ例
- docs(tools): AgentTcpTestClient の仕様書を追加

