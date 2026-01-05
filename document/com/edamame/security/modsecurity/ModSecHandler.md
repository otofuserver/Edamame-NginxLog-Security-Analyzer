# ModSecHandler

対象: `src/main/java/com/edamame/security/modsecurity/ModSecHandler.java`

## 概要
- ModSecurity ログの解析と ModSecurityQueue への登録、およびアクセスログとの即時／定期的な照合を行うユーティリティクラス。
- raw log から rule id、msg、data、severity、URL などを抽出し、即時でアクセスログとの照合を試み、マッチしなければキューへ登録するフローを提供する。

## 主な機能
- raw ModSecurity ログのパース（rule id / msg / data / severity / URL 抽出）
- ModSecurityQueue へのアラート登録または即時紐づけ（processModSecurityAlertToQueue）
- 定期的なアクセスログ照合タスクの実行（startPeriodicAlertMatching）
- 一致したアラートの DB 保存（saveModSecurityAlertToDatabase）

## 挙動（更新点）
- `processModSecurityAlertToQueue` は raw log をパースした上で次の処理を行う：
  1. 抽出された URL が `/favicon.ico` 相当であれば破棄し、キューには登録しない。
  2. 抽出時刻（検出時刻）を基準に、まず同一秒（秒単位で差=0）のアクセスログを検索して一致を試みる。
  3. 同一秒で見つからなければ ±30 秒の範囲で再検索し、最も時間差が小さい一致を採用する。
  4. 即時照合で一致した場合は、その `access_log` の `blocked_by_modsec` を true に更新し、`modsec_alerts` テーブルに保存する。
  5. 即時照合で一致しなかった場合は、`ModSecurityQueue.addAlert(...)` でキューに追加して一定時間（デフォルトは 30 秒、設定可能）保持する。これにより後続のアクセスログと照合できるようにする。

- `startPeriodicAlertMatching` は従来どおり ScheduledExecutorService 上で定期的に `performPeriodicAlertMatching` を実行し、キュー中のアラートを最近のアクセスログと照合して保存する。即時照合で紐づかなかったアラートはここで照合され得る。

## 細かい指定された仕様
- ブロック判定のためのパターンは `BLOCK_PATTERNS` 配列で定義されている（将来的に利用）。
- ルール ID / メッセージ / data / severity は正規表現で抽出し、見つからない場合はデフォルト値を設定する。
- `processModSecurityAlertToQueue` のシグネチャは `processModSecurityAlertToQueue(String rawLog, String serverName, ModSecurityQueue modSecurityQueue)` に変更され、呼び出し側は現在の `ModSecurityQueue` インスタンスを渡して処理を委譲する。
- URL 抽出は複数パターンを優先順で試行し、`normalizeModSecUrl` / `normalizeUrlForComparison` によってデコード・正規化して比較する。
- 即時照合は例外が発生しても処理が止まらないように内部でハンドリングされる。即時紐づけ失敗時はキューへの追加を試み、キュー追加に失敗しても例外を投げずログ出力に留める。

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

- `private static String normalizeUrlForComparison(String url)`
  - アラート URL とアクセスログ URL の比較用にデコード・小文字化・エンティティ復元などを行う。

- `public static void processModSecurityAlertToQueue(String rawLog, String serverName, ModSecurityQueue modSecurityQueue)`
  - raw log を抽出し、即時紐づけ（同一秒→±30秒）を試みる。紐づけば DB に保存、紐づかなければ `modSecurityQueue.addAlert` でキューへ登録する。favicon は破棄。

- `public static void saveModSecurityAlertToDatabase(Long accessLogId, ModSecurityQueue.ModSecurityAlert alert)`
  - 一致したアラートを DB に保存するユーティリティ（DbService 経由で保存を呼ぶ）。

## その他
- 即時照合は access_log 側が先に保存されるケースと、ModSecurity ログ側が先に出力されるケース双方を考慮した設計になっている。即時照合で紐づかない場合にキューへ追加することで、後続の access_log に対して一定時間内に関連付けが行えるようにした。
- DB 負荷やキュー保持期間のバランスに注意すること。保持期間は将来的に設定化することを推奨。

## 変更履歴
- 1.0.1 - 2025-12-30: 新規ドキュメント作成（ソースに基づく）
- 1.0.2 - 2026-01-05: 即時紐づけの追加とキュー保持動作の変更
  - `processModSecurityAlertToQueue` を更新：同一秒→±30秒の即時照合を行い、紐づかなければ `ModSecurityQueue.addAlert` でキューに保存するように変更。
  - `/favicon.ico` 相当のアラートを破棄する振る舞いを追加。
  - `processModSecurityAlertToQueue` のシグネチャに `ModSecurityQueue` 引数を追加。

## コミットメッセージ例
- feat(modsecurity): 即時紐づけ（同一秒→±30秒）を ModSecHandler に実装し、未紐づけアラートをキューに保持するように変更
