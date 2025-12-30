# LogCollector

対象: `src/main/java/com/edamame/agent/log/LogCollector.java`

## 概要
- NGINX アクセスログ（ホスト上またはコンテナ内）を監視して新規ログを収集するコンポーネント。ログファイルのオフセット管理、ローテーション検出、ログ行パース（正規表現）を行い、`LogEntry` インスタンスのリストを返す。

## 主な機能
- ログファイルのオフセット管理（位置ファイルに保存）
- ログファイルのローテーション検出と対応
- NGINX ログ行のパース（設定に基づく正規表現）
- ModSecurity エラー行の判別と生ログエントリ化

## 挙動
- 初期化時に設定から監視対象ログパスを取得し、位置ファイル（jar と同ディレクトリに edamame-agent-positions.txt）から既読位置を読み込む。
- collectNewLogs() を呼ぶと各ログパスを順に走査し、新規行を parseLogLine() で LogEntry に変換して返却する。
- ファイルサイズが以前のオフセットより小さくなった場合はローテーションと判断し、オフセットを 0 にリセットする。

## 細かい指定された仕様
- 位置情報は "path:position" のテキストファイルで保持する。
- 既知の ModSecurity 行（"ModSecurity:" かつ "Access denied" を含む）については生ログとして扱う。
- NGINX ログ形式は設定（`AgentConfig.getLogFormat()`）から取得し、簡易的な正規表現でパースする（必要に応じてパターンを調整すること）。

## メソッド一覧と機能
- `public LogCollector(AgentConfig config)` - コンストラクタ（設定読み込み、位置読み込み）
- `public List<LogEntry> collectNewLogs()` - 監視対象すべてから新規ログを収集して返す
- `private List<LogEntry> collectLogsFromFile(String logPath)` - 指定ファイルから新規行を収集
- `private LogEntry parseLogLine(String line, String sourcePath)` - 行パース
- `private void loadFilePositions()` / `private void saveFilePositions()` - 位置ファイルの読み書き
- `private String extractServerName(String logPath)` - ログパスからサーバ名を特定（設定に依存）

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(agent): LogCollector の仕様書を追加

