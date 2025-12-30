# EdamameAgent
- docs(agent): EdamameAgent の仕様書を追加
## コミットメッセージ例

- 1.0.0 - 2025-12-31: ドキュメント作成
## 変更履歴

- 長時間動作するプロセスのため、シャットダウンフックを設定して安全に停止できるようにしている。ログや再接続動作は監視下で運用すること。
## その他（運用注意）

- `public static void main(String[] args)` - エントリポイント
- `public void stop()` - エージェント停止（クリーンアップ）
- `private void sendHeartbeat()` - heartbeat を送信
- `private void manageIptables()` - IptablesManager でブロック要求処理
- `private void collectAndTransmitLogs()` - LogCollector で収集して LogTransmitter に送信
- `private void initialServerConnection()` - 初回接続試行および登録処理
- `public void start()` - エージェント開始（初期化・スケジュール登録）
- `public EdamameAgent(String configPath)` - コンストラクタ（設定ロード用パス受け取り）
## メソッド一覧と機能（主なもの）

- 各処理は例外を捕捉してログ出力し、エージェント全体が落ちないようにする。
- 起動・停止ログは `AgentLogger` 経由で出力する。
- 再接続はログ送信側（LogTransmitter）の再接続コールバックと連携して行われる。
## 細かい指定された仕様

- 停止時は executor をシャットダウンし、登録解除や transmitter のクリーンアップを行う。
- 初回のサーバ接続を非同期で試み、成功したら登録（registration）を行う。失敗時は再接続モードへ移行する。
- 定期タスク（ログ収集/iptables 管理/heartbeat 送信）を ScheduledExecutorService 上で実行する。
- 起動時に `AgentConfig` を読み、`LogCollector`, `LogTransmitter`, `IptablesManager` を初期化する。
## 挙動

- グレースフルシャットダウン（shutdown hook）
- サーバ接続の初回試行・再接続モードの管理
- iptables 管理の定期実行
- ログ収集／送信の定期実行（スケジュール登録）
- 設定ファイル読み込みとサービス初期化（`AgentConfig`）
## 主な機能

- エダマメエージェントのメインクラス。ローカルで NGINX ログを収集し、サーバ（Edamame）へ TCP で送信、iptables によるブロック管理を行う。起動・停止ライフサイクル、スケジューラ登録、コンポーネント（LogCollector / LogTransmitter / IptablesManager）組み立てを担う。
## 概要

対象: `src/main/java/com/edamame/agent/EdamameAgent.java`


