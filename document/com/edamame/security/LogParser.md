# LogParser

対象: `src/main/java/com/edamame/security/LogParser.java`

## 概要
- NGINX（および syslog 経由の nginx 出力）ログ行のパースユーティリティ。複数のログ形式に対応する正規表現パターン群を持ち、Log 行から IP/タイムスタンプ/リクエスト/ステータス等を抽出する。

## 主な機能
- 複数形式（Combined, Common, syslog error 等）に対応したパターンマッチング
- IP（IPv4/IPv6）妥当性チェック
- ModSecurity エラーログや重複メッセージの簡易フィルタリング
- nginx タイムスタンプ（dd/MMM/yyyy:HH:mm:ss +zzzz 形式）の解析

## 挙動
- `parseLogLine(String line)` が主入口。空行や不正行、重複 message 行は null を返してスキップする。
- 定義された順にパターンを試行し、最初にマッチしたパターンで `parseWithPattern` を呼んで Map を返す。
- IPv4/IPv6 の簡易妥当性チェックを行い不正 IP の場合は解析を中止する。

## 細かい指定された仕様
- ログのエラーパターン（file open, permission denied など）はスキップ対象として列挙されている。
- syslog タイムスタンプは現在年を用いて LocalDateTime に変換する（年情報がないため）。
- 解析失敗や例外は AppLogger でデバッグ/警告出力される。

## メソッド一覧と機能
- `public static Map<String,Object> parseLogLine(String line)` - エントリポイント（解析成功時 Map、失敗時 null）
- `private static Map<String,Object> parseWithPattern(Matcher matcher, int patternIndex, String originalLine, BiConsumer<String,String> log)` - パターン別解析
- `private static boolean isInvalidIp(String ipStr)` - IP 妥当性チェック
- `private static LocalDateTime parseNginxTimestamp(String timeStr)` / `parseSyslogTimestamp(String line)` - タイムスタンプ解析
- `private static String[] parseRequestLine(String requestLine)` - request 行の分解（method, url）

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(security): LogParser の仕様書を追加

