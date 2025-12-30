# AgentConfig

対象: `src/main/java/com/edamame/agent/config/AgentConfig.java`

## 概要
- エージェントの設定管理クラス。JSON 設定ファイルを読み込み、エージェント動作に必要な各種設定（Edamame 接続情報、ログ収集設定、iptables 設定、ハートビート等）を提供するユーティリティ。
- 設定のデフォルト生成、旧設定の互換サポート、設定パースの堅牢化（ログ出力を伴う検証）を行う。

## 主な機能
- 設定ファイルの読み込み・解析（`load()`）
- 設定のセクション別読み込み（`loadAgentConfig`, `loadEdamameConfig`, `loadLoggingConfig`, `loadIptablesConfig`, `loadHeartbeatConfig`, `loadAdvancedConfig`）
- TCP 接続の軽量テスト（`testTcpConnection`）
- サーバー側ログ設定（`servers` 配列）をパースして `nginxLogPaths` と `serverLogMappings` を構築
- 位置情報や既存ファイルの存在確認（`parseServerConfig`）
- デフォルト設定ファイルの作成（`createDefaultConfig`）
- 設定値の getter を提供

## 挙動
- `load()` は指定された JSON 設定ファイルを読み、各セクションの読み込み関数を順に呼ぶ。ファイルが存在しない場合は `createDefaultConfig()` でデフォルト設定を作成して成功とする。
- `loadLoggingConfig()` にて `servers` 配列の各行をパースし、`serverName,logPath` 形式の行を登録する。空行や `#` で始まる行はスキップする。
- `testTcpConnection()` はサーバへ簡易接続テストメッセージ（MSG_TYPE_CONNECTION_TEST）を送信し、成功可否を判定する。
- 設定読み込み時は `AgentLogger` で詳細なデバッグ情報や警告・エラーを出力し、読み込みの問題を可視化する。

## 細かい指定された仕様
- `logging.servers` は配列で、コメント行（先頭 `#`）や空行を許容する。各有効行は `serverName,logPath` でなければスキップされる。
- `logging.debugMode` によって `AgentLogger` のデバッグ出力が有効化される（旧 `advanced.debugMode` は廃止だが互換サポートあり）。
- `createDefaultConfig()` は JSON の雛形を `configPath` に書き出す（読み込み失敗時のフォールバック動作）。
- `getServerNameByLogPath(String logPath)` は登録済みの `serverLogMappings` を参照してサーバ名を返す。見つからない場合は `defaultServerName` を返す。
- 設定ファイルに `advanced` セクションがあっても警告ログを出し、`logging` セクションを優先する（互換処理のみ）。

## メソッド一覧と機能（主なもの）
- `public AgentConfig(String configPath)`
  - コンストラクタ。設定ファイルパスを受け取り ObjectMapper を初期化する。

- `public boolean load()`
  - 設定ファイルを読み込み、各セクションをパースする。成功可否を返す。

- `private void loadAgentConfig(JsonNode root)`
  - `agent` セクションを読み込み（name, description, ipAddress）。

- `private void loadEdamameConfig(JsonNode root)`
  - `edamame` セクションを読み込み（host, port, protocol, apiKey, useSSL 等）。

- `private void loadLoggingConfig(JsonNode root)`
  - `logging` セクションを読み込み（collectionInterval, format, maxBatchSize, servers 配列をパース）。

- `private void loadIptablesConfig(JsonNode root)`
  - `iptables` セクションを読み込み（enabled, checkInterval, chain, blockDuration）。

- `private void loadHeartbeatConfig(JsonNode root)`
  - `heartbeat` セクションを読み込み（interval）。

- `private void loadAdvancedConfig(JsonNode root)`
  - 廃止済みの `advanced` セクションを互換的に処理（警告ログ出力）。

- `public boolean testTcpConnection()`
  - サーバへ接続テスト用メッセージを送信し、レスポンスで成功を判定する。

- `public void parseServerConfig()`
  - `nginxLogPaths` の各パスが存在するか確認してログ出力する。

- `private void createDefaultConfig()`
  - デフォルト設定 JSON を `configPath` に書き出す。

- Getter 一式（`getAgentId`, `getAgentIpAddress`, `getEdamameHost`, `getEdamamePort`, `getApiKey`, `getNginxLogPaths`, `getServerLogMappings`, `getServerNameByLogPath` 等）

## エラーハンドリング
- ファイル I/O や JSON パースの例外はキャッチして `AgentLogger.error` を出力し、`load()` は false を返す。`createDefaultConfig()` の失敗はログにエラーメッセージを出力する。

## 変更履歴
- 1.2.0 - 2025-12-31: ドキュメント作成（実装に基づく）

## コミットメッセージ例
- docs(agent): AgentConfig の仕様書を追加

