# AgentTcpServer

対象: `src/main/java/com/edamame/security/agent/AgentTcpServer.java`

## 概要
- ポート2591でエージェントからのTCP接続を受け付けるサーバー実装。
- ログバッチ受信・ModSecurityキュー照合・URL登録/最新メタデータ更新・セキュリティアクション実行までを担当。

## 主な変更（2026-01-15）
- 既存URLへの再アクセス時にも `updateUrlRegistryLatest` を呼び出し、`latest_access_time`/`latest_status_code`/`latest_blocked_by_modsec` を同期。
- ModSecurity一致判定結果を `parsedLog.blocked_by_modsec` に反映し、URL登録や最新情報更新に渡すよう修正。

## 主な処理フロー
- 認証（APIキー検証）後にセッションを作成し、メッセージタイプ別に処理。
- `handleLogBatch` → `processLogEntries` 内で以下を実施:
  - access.logのみ処理し、`insertAccessLog` で DB 登録
  - ModSecurityアラートとの照合後、`updateAccessLogModSecStatus` / `updateUrlRegistryLatest` でDBを同期
  - 攻撃パターン識別（`AttackPattern.detectAttackTypeYaml`）とURL登録（`registerUrlRegistryEntry`）
  - 既存URLは登録スキップしつつ最新メタデータを更新

## コミットメッセージ例
- docs(agent): AgentTcpServer の仕様を更新（URL再アクセス時の最新メタ同期）
