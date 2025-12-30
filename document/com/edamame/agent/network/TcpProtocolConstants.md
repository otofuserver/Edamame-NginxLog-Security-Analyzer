# TcpProtocolConstants

対象: `src/main/java/com/edamame/agent/network/TcpProtocolConstants.java`

## 概要
- Agent とサーバ間で利用する TCP プロトコルの定数定義クラス。メッセージタイプ、レスポンスコード、タイムアウト等を一元管理する。

## 定義されている定数（主なもの）
- メッセージタイプ
  - `MSG_TYPE_LOG_BATCH = 0x01`
  - `MSG_TYPE_HEARTBEAT = 0x02`
  - `MSG_TYPE_BLOCK_REQUEST = 0x03`
  - `MSG_TYPE_AUTH = 0x04`
  - `MSG_TYPE_CONNECTION_TEST = 0x09`
  - `MSG_TYPE_REGISTER = 0x10`
  - `MSG_TYPE_UNREGISTER = 0x11`
- レスポンスコード
  - `RESPONSE_SUCCESS = 0x00`
  - `RESPONSE_ERROR = 0x01`
  - `RESPONSE_AUTH_FAILED = 0x02`
- その他
  - `VALID_API_KEY = "edamame-agent-api-key-2025"`（テスト用の既定値）
  - `SOCKET_TIMEOUT = 30000`（ms）
  - `MAX_MESSAGE_SIZE = 10MB`
  - `CONNECTION_TIMEOUT = 300000`（ms）

## 使用上の注意
- 定数はプロトコル互換性のためサーバ・エージェント双方で共有される。変更は後方互換性に注意すること。

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(agent): TcpProtocolConstants の仕様書を追加

