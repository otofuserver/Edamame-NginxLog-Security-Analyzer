# ActionEngine

対象: `src/main/java/com/edamame/security/ActionEngine.java`

## 概要
- 検知・スケジュール・トリガーに応じた自動アクション（メール送信、iptables 追加、Webhook、Cloudflare 操作など）を実行するエンジン。DB 上のルールを読み取り条件マッチしたルールを順に実行する。

## 主な機能
- ルール検索（DB）と条件評価 (`getMatchingRules`, `matchesConditionParams`)
- アクション実行（メール、iptables、Cloudflare、Webhook など）
- 手動/自動トリガー（攻撃検知時・定期レポート時）に対する処理
- 実行結果のログ記録と統計更新

## 挙動
- `executeActionsOnAttackDetected` や `executeScheduledReport` などの上位 API が呼ばれると、DB から一致ルールを取得して順に `executeRule` を呼ぶ。
- ルールの `conditionParams` は JSON で格納され、各条件タイプ（attack_detected, ip_frequency, status_code, custom）ごとの評価ロジックに渡す。
- メール送信などは SMTP 設定を参照して実行する。SMTP 到達性チェック等のプレチェックを行い失敗時はスキップする。

## 細かい指定された仕様
- ルール取得は優先度（priority）昇順で取得し、DB 側で tool の有効/無効をチェックする。
- 条件パラメータが空の場合は常にマッチと見なす。
- 実行結果は `logExecutionResult` で DB に記録される（実装内で行われる想定）。
- 将来的に IP 頻度やステータスコードベースの条件が追加実装される旨がコメントで示されている。

## メソッド一覧と機能（主なもの）
- `public ActionEngine()` - コンストラクタ（SMTP 設定等の初期化）
- `public void executeActionsOnAttackDetected(...)` - 攻撃検知時の呼び出しポイント
- `public void executeScheduledReport(String serverName, String reportType)` - 定期レポート時の呼び出しポイント
- `private List<ActionRule> getMatchingRules(String serverName, String conditionType, Map<String,Object> eventData)` - DB から一致ルール取得
- `private void executeRule(ActionRule rule, Map<String,Object> eventData)` - ルール実行の統括
- `private String executeMailAction(ActionRule rule, Map<String,Object> eventData)` - メール送信処理（サンプル）
- 条件評価ヘルパ（`isAttackConditionMatching`, `isIpFrequencyConditionMatching`, ...）

## 変更履歴
- 2.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(security): ActionEngine の仕様書を追加

