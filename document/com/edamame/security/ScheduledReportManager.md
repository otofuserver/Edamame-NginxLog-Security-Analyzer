# ScheduledReportManager
- docs(security): ScheduledReportManager の仕様書を追加
## コミットメッセージ例

- 1.0.0 - 2025-12-31: ドキュメント作成
## 変更履歴

- ユーティリティ: `calculateInitialDelay`, `calculateInitialDelayForWeekly`, `calculateInitialDelayForMonthly`
- `public void shutdown()` - スケジューラ停止
- `public void executeManualReport(String reportType, String serverName)` - 手動実行
- `private void executeReport(String reportType, String serverName)` - レポート実行本体
- `public void startScheduledReports()` - 定期ジョブの登録開始
- `public ScheduledReportManager(ActionEngine actionEngine)` - コンストラクタ
## メソッド一覧と機能

- シャットダウン時は最長 10 秒待って終了させ、タイムアウト時は強制停止する
- 月次: 毎月 1 日 10:00 実行（概算で 30 日間隔設定）
- 週次: 毎週特定の曜日（例: 月曜）09:00 実行
- 日次: 毎日 08:00 実行
## 細かい指定された仕様

- 実行ログやエラーは `AppLogger` に記録する。遅延計算は現在時刻から目標時刻までの秒数を算出してスケジュールする。
- 各ジョブは `executeReport(reportType, serverName)` を呼び、`ActionEngine.executeScheduledReport` を実行する。
- 起動時 `startScheduledReports()` で 3 種類（日次8:00、週次（例: 月曜）9:00、月次1日10:00）を ScheduledExecutorService に登録する。
## 挙動

- スケジューラの安全なシャットダウン
- 手動実行 API（`executeManualReport`）
- 日次/週次/月次レポートの定期実行スケジューリング
## 主な機能

- 定期レポート送信を管理するスケジューラ。日次・週次・月次のタイミングで `ActionEngine` に統計レポート実行を委譲する。
## 概要

対象: `src/main/java/com/edamame/security/ScheduledReportManager.java`


