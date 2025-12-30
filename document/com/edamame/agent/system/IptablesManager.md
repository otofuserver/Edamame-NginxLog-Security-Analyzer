# IptablesManager

対象: `src/main/java/com/edamame/agent/system/IptablesManager.java`

## 概要
- Linux の `iptables`（および Windows の場合は Windows Firewall）を操作して IP アドレスのブロック・解除を行うエージェント側のユーティリティクラス。
- サーバからのブロック指示（BlockRequest）を受け取り、OS に応じたコマンドを実行してローカルファイアウォールを操作する。

## 主な機能
- iptables チェーン `EDAMAME_BLOCKS` の初期化（`initializeFirewallChain`）
- ブロック要求のポーリングおよび処理（`processBlockRequests`）
- 個別 IP のブロック/解除（`blockIpAddress`, `unblockIpAddress`）
- 期限切れブロックの自動クリーンアップ（`cleanupExpiredBlocks`）

## 挙動
- Linux の場合は `iptables` コマンドを実行して `EDAMAME_BLOCKS` チェーンに DROP ルールを挿入/削除する。
- Windows の場合は `netsh advfirewall` コマンドでルールを追加/削除する。
- コマンド実行結果の exit code と標準エラーを解析して詳細ログを出力し、権限不足（root 必要）の場合は注意喚起ログを出力する。

## 細かい指定された仕様
- チェーンの作成、末尾に `-j RETURN` の有無確認を行い、必要なら追加する。
- コマンドの実行は `executeCommand`（配列） / `executeCommand`（文字列）を利用し、出力のログを詳細に取得して判定する。
- BlockRequest の JSON 解析は柔軟に行い、配列型/オブジェクト型いずれにも対応する。
- `activeBlocks` マップで現在有効なブロックとその期限を保持する。

## メソッド一覧と機能
- `public IptablesManager(AgentConfig config, LogTransmitter logTransmitter)` - コンストラクタ
- `private void initializeFirewallChain()` - 初期チェーン作成
- `public void processBlockRequests()` - ブロック要求の取得と処理
- `private void processBlockRequest(BlockRequest request)` - 個別処理
- `private void blockIpAddress(String ipAddress, int durationMinutes)` - IP ブロック
- `private void unblockIpAddress(String ipAddress)` - IP ブロック解除
- `public void cleanupExpiredBlocks()` - 期限切れブロックのクリーンアップ

## その他
- 実行環境により root 権限が必要となるため、デプロイ時の実行権限に注意すること。
- コマンド実行の失敗時は詳細ログを残し、必要に応じて運用者へ通知する設計を推奨する。

## 変更履歴
- 1.1.0 - 2025-12-30: ドキュメント作成

## コミットメッセージ例
- docs(agent): IptablesManager の仕様書を追加

