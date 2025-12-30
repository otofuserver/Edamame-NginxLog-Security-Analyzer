# AgentSession

対象: `src/main/java/com/edamame/security/agent/AgentSession.java`

## 概要
- エージェントとの個別 TCP セッションを表現・管理するクラス。
- ソケットと入出力ストリームを保持し、登録 ID や最終アクティビティ時刻を追跡する。

## 主な機能
- エージェント名・登録ID の管理
- 最終アクティビティ時刻の更新と取得
- 同期化された送信（sendResponse）とセッションのクローズ処理
- セッションの文字列表現（toString）

## 挙動
- `sendResponse` は synchronized で実装されており、レスポンスコード + メッセージ長 + メッセージ本体を順に送信する。
- 送信に失敗した場合はセッションを inactive に設定し例外をスローする。
- `close` は入出力ストリームとソケットを安全にクローズし、active フラグを false に設定する。例外は呼び出し元でログ処理される想定。

## 細かい指定された仕様
- 文字列は常に UTF-8 で送受信する。
- `getLastActivityMillis` は UTC ベースのエポックミリ秒を返す。
- `sendResponse` は呼び出し時にセッションがアクティブでない場合 IOException を投げる。
- `close` は例外をスローしない（クリーンアップ処理を優先）。

## メソッド一覧と機能（主なもの）
- `public AgentSession(String agentName, Socket socket, DataInputStream input, DataOutputStream output)`
  - コンストラクタ。ソケットと入出力ストリーム、エージェント名を受け取りセッションを初期化する。

- `public String getAgentName()`
  - エージェント名を返す。

- `public String getRegistrationId()`
  - 登録IDを返す。

- `public void setRegistrationId(String registrationId)`
  - 登録IDを設定する。

- `public LocalDateTime getLastActivity()`
  - 最終アクティビティ時刻を返す。

- `public long getLastActivityMillis()`
  - 最終アクティビティ時刻をミリ秒で返す（UTC）。

- `public void updateLastActivity()`
  - 最終アクティビティ時刻を現在時刻に更新する。

- `public boolean isActive()`
  - セッションがアクティブかどうかを返す。（ソケットの状態も確認）

- `public synchronized void sendResponse(byte responseCode, String message) throws IOException`
  - レスポンスを送信する。送信失敗時は IOException を投げ、内部的に active を false にする。

- `public void close()`
  - 入出力ストリームとソケットをクローズし、セッションを終了する。例外は内部で捕捉し、呼び出し元でログ出力する設計。

- `@Override public String toString()`
  - セッション情報の文字列表現を返す（デバッグ用）。

## その他
- Jackson 等のシリアライズ対象ではないため、デフォルトコンストラクタ・getter/setter の追加は不要だが、将来的に API 経由でセッション情報を出力する場合は DTO を用意すること。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規ドキュメント作成

## コミットメッセージ例
- docs: AgentSession の仕様書を追加

