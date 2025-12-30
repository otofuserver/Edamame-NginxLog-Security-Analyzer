# LogEntry

対象: `src/main/java/com/edamame/agent/log/LogEntry.java`

## 概要
- 収集した NGINX アクセスログを表現するデータクラス（Java record）。JSON シリアライズを考慮して LocalDateTime を文字列化して保持する。

## フィールド（record コンポーネント）
- `String clientIp`
- `String timestamp`（元のログのタイムスタンプ）
- `String request`（HTTP メソッド + URL + バージョン）
- `int statusCode`
- `String responseSize`（バイト数または "-"）
- `String referer`
- `String userAgent`
- `String sourcePath`（ログファイルパス）
- `String serverName`（ログを収集したサーバ名）
- `String collectedAt`（収集時刻の ISO 文字列）
- `boolean blockedByModSec`

## 主な機能（ユーティリティ）
- `static LogEntry createWithCurrentTime(...)` - 現在時刻で LogEntry を作成
- `static LogEntry createModSecurityBlocked(...)` - ModSecurity ブロック付きの LogEntry を作成
- `String getHttpMethod()`, `String getRequestUrl()`, `String getHttpVersion()` - request をパースして返すヘルパ
- `boolean isErrorResponse()`, `boolean isServerError()`, `boolean isClientError()` - ステータスコード判定
- `long getResponseSizeBytes()` - responseSize を数値として返す（不正値は 0）
- `String getSummary()` / `String toString()` - サマリ／JSON 表現の出力

## 設計意図
- Jackson や他のシリアライザ互換性のため、LocalDateTime を直接持たずに ISO 形式の文字列で保持する設計。
- レコードの不変性と簡潔な生成を優先している。

## 変更履歴
- 1.2.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(agent): LogEntry の仕様書を追加

