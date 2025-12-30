# ModSecHandler

対象: `src/main/java/com/edamame/security/modsecurity/ModSecHandler.java`

## 概要
- ModSecurity ログの解析と ModSecurityQueue への登録、およびアクセスログとの定期的な照合を行うユーティリティクラス。
- raw log から rule id、msg、data、severity、URL などを抽出し、キューへ格納、定期照合で一致が見つかれば DB へ保存するフローを提供する。

## 主な機能
- raw ModSecurity ログのパース（rule id / msg / data / severity / URL 抽出）
- ModSecurityQueue へのアラート登録（processModSecurityAlertToQueue）
- 定期的なアクセスログ照合タスクの実行（startPeriodicAlertMatching）
- 一致したアラートの DB 保存（saveModSecurityAlertToDatabase）

## 挙動
- `processModSecurityAlertToQueue` は raw log をパースして抽出情報を得た上で `ModSecurityQueue.addAlert` を呼ぶ。
- `startPeriodicAlertMatching` は ScheduledExecutorService 上で定期的に `performPeriodicAlertMatching` を実行し、最近の access_log とキュー中のアラートを照合する。
- 照合により一致が見つかった場合は access_log の `blocked_by_modsec` を更新し、`modsec_alerts` テーブルにアラートを保存する。

## 細かい指定された仕様
- ブロック判定のためのパターンは `BLOCK_PATTERNS` 配列で定義されている。大文字小文字を無視してマッチする。
- ルール ID / メッセージ / data / severity は正規表現で抽出し、見つからない場合はデフォルト値を設定する。
- 定期照合は例外が発生してもタスクが停止しないようにハンドリングされている。
- アラートの URL 抽出は複数パターンを優先順で試行し、最も適切なものを選択して正規化する。

## メソッド一覧と機能（主なもの）
- `public static void startPeriodicAlertMatching(ScheduledExecutorService executor, ModSecurityQueue modSecurityQueue)`
  - 定期照合タスクを executor 上で開始する。タスクは例外を捕捉して継続する設計。

- `private static void performPeriodicAlertMatching(ModSecurityQueue modSecurityQueue)`
  - 最近の access_log を取得し、キュー内のアラートと照合して一致を検出・処理する。

- `public static boolean isModSecurityRawLog(String logLine)`
  - 与えられた行が ModSecurity ログかどうかの簡易判定を行う（`logLine.contains("ModSecurity:")`）。

- `public static Map<String, String> extractModSecInfo(String rawLog)`
  - raw log からルール ID / msg / data / severity / url 等を抽出して Map で返す。

- `private static String extractUrlFromModSecLog(String rawLog)`
  - raw log から URL を抽出する内部ユーティリティ（複数の正規表現を優先順で試行）。

- `private static String normalizeModSecUrl(String url)`
  - 抽出された URL を正規化（デコードや先頭スラッシュ付与など）する内部ユーティリティ。

- `private static boolean isValidUrl(String url)`
  - 抽出された URL が有効かどうかを判定する内部ロジック。

- `public static void processModSecurityAlertToQueue(String rawLog, String serverName, ModSecurityQueue modSecurityQueue)`
  - raw log を抽出し、キューへ登録する高レベル関数。

- `public static void saveModSecurityAlertToDatabase(Long accessLogId, ModSecurityQueue.ModSecurityAlert alert)`
  - 一致したアラートを DB に保存するユーティリティ（DbService 経由で保存を呼ぶ）。

## その他
- 定期照合処理はアクセスログを取得するために DB を参照するため、DB 負荷と照合頻度のバランスに注意すること。
- raw log の形式は環境や ModSecurity のバージョンに依存するため、抽出正規表現のメンテナンスが必要。

## 変更履歴
- 1.0.1 - 2025-12-30: 新規ドキュメント作成（ソースに基づく）

## コミットメッセージ例
- docs(modsecurity): ModSecHandler の仕様書を追加

