# ModSecurityQueue

対象: `src/main/java/com/edamame/security/modsecurity/ModSecurityQueue.java`

## 概要
- ModSecurity のアラート（Access denied 等）を一時的に保持し、アクセスログと時間・URLで関連付けを行うキュー管理コンポーネント。
- サーバー単位でキューを管理し、一定時間経過した古いアラートは自動でクリーンアップする。

## 主な機能
- サーバー別のアラートキュー保持
- アラート追加（raw log と抽出情報）
- 指定アクセスログに一致するアラートの検索と取得（一致したアラートはキューから除去）
- 期限切れアラートの定期クリーンアップ
- キュー状態の取得（デバッグ用）

## 挙動
- `addAlert` で受け取った raw log と抽出情報（rule id, msg, data, severity, url 等）を `ModSecurityAlert` レコードとしてサーバー単位のキューへ格納する。
- `findMatchingAlerts` は指定したサーバーのキューを走査し、アクセス時刻からの時間差（デフォルト 60 秒以内）と URL 一致を元にマッチを判定する。マッチしたアラートは結果に含め、キューから除去する。
- `startCleanupTask` に渡した ScheduledExecutorService 上で定期的に `cleanupExpiredAlerts` を呼び、一定時間より古いアラートを削除する。

## 細かい指定された仕様
- アラート保持期間は定数 `ALERT_RETENTION_SECONDS`（ソースでは 60 秒）で定義される。
- キューはサーバー名ごとに `ConcurrentLinkedQueue` を用いて管理する（スレッドセーフ設計）。
- `ModSecurityAlert` は record として定義され、JSON シリアライズ（Jackson 等）での利用を想定してデフォルトコンストラクタ/getter は不要だがコメントで保持理由を明記している。
- URL 抽出は raw log の複数パターンを試行して行い、失敗時は空文字列を返す（フォールバック）。
- 例外は基本的に内部で捕捉してログ出力し、呼び出し側に例外を投げない挙動を優先する（堅牢性重視）。

## メソッド一覧と機能（主なもの）
- `public void startCleanupTask(ScheduledExecutorService executor)`
  - 定期クリーンアップタスクを executor 上で起動する。例外発生時も継続するようにハンドリングされている。

- `public synchronized void addAlert(String serverName, Map<String,String> extractedInfo, String rawLog)`
  - ModSecurity の抽出情報と raw log を受け取り、サーバー別キューに `ModSecurityAlert` を追加する。

- `public synchronized List<ModSecurityAlert> findMatchingAlerts(String serverName, String method, String fullUrl, LocalDateTime accessTime)`
  - 指定されたリクエスト情報（serverName, method, fullUrl, accessTime）に一致するアラートを検索して返す。マッチしたアラートはキューから削除される。

- `public synchronized void cleanupExpiredAlerts()`
  - 各キューについて保持期間を超えたアラートを削除する。

- `private String extractUrlFromRawLog(String rawLog)`
  - raw log から URL を抽出する内部ユーティリティ（複数の正規表現を試行する）。

- `private String normalizeExtractedUrl(String url)`
  - 抽出した URL を正規化（デコードや先頭スラッシュ付与等）する内部ユーティリティ。

- `private boolean isUrlMatching(String alertUrl, String requestUrl, String method)`
  - アラートから抽出した URL とリクエスト URL の単純一致／部分一致を判定する内部ロジック。

- `public synchronized Map<String,Integer> getQueueStatus()`
  - サーバーごとのキューサイズを返す（デバッグ・監視用）。

## その他
- 本クラスは ModSecurity とアクセスログを結び付ける要となるため、パフォーマンスとメモリ使用量に注意すること（大量アラート到着時の影響）。
- 将来的にキューの永続化や外部ストレージ移行をする場合はキー設計・削除ポリシーを慎重に検討する。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（ソースに基づく仕様書）

## コミットメッセージ例
- docs(modsecurity): ModSecurityQueue の仕様書を追加

