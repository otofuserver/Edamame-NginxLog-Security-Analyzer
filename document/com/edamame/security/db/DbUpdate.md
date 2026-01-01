# DbUpdate

対象: `src/main/java/com/edamame/security/db/DbUpdate.java`

## 概要
- UPDATE 系のデータベース操作（サーバー情報更新、エージェント統計更新、アクセスログの ModSecurity 状態更新、URL ホワイトリスト化、ロール階層管理など）を集約するユーティリティクラスの仕様。
- `DbSession` を受け取り、トランザクション制御や例外ラップを行いつつ SQL を実行する設計。

## 主な機能
- サーバー情報更新（`updateServerInfo`）
- サーバー最終ログ受信時刻更新（`updateServerLastLogReceived`）
- エージェントハートビート更新（`updateAgentHeartbeat`）
- エージェントログ統計更新（`updateAgentLogStats`）
- access_log の ModSecurity フラグ更新（`updateAccessLogModSecStatus`）
- エージェントの inactive 化（`deactivateAgent`, `deactivateAllAgents`）
- URL をホワイトリスト化する更新（`updateUrlWhitelistStatus`）
- サーバーごとのデフォルトロール階層追加（`addDefaultRoleHierarchy`）

## 挙動
- 各メソッドは `DbSession.execute` または `DbSession.executeWithResult` を用いてデータベース操作を行う。SQL 実行中に発生した `SQLException` は内部で `RuntimeException` にラップして上位に伝播する。
- 更新処理の成否は `AppLogger` で INFO/WARN/ERROR に分けてログされる。成功時には debug/info ログ、失敗時には error/warn ログを出力する。
- ロール階層の追加は既存データを読み取り JSON 配列をマージして更新する（Jackson を利用して JSON をパース/シリアライズする）。

## 細かい指定された仕様
- SQL は PreparedStatement を用いてパラメータバインドし、SQL インジェクションを防止する。
- 各更新メソッドは可能な限り idempotent（繰り返し実行しても重大な副作用を起こさない）ことを目標とする。
- 大きな変更（複数テーブルにまたがる）を行う場合は呼び出し側でトランザクションを開始し、まとめて実行すること。
- JSON カラム（例: `roles.inherited_roles`）へは Jackson の ObjectMapper で安全にパース・シリアライズして格納する。
- ログ出力ルール：
  - 成功時: AppLogger.info / AppLogger.debug
  - 軽微な問題: AppLogger.warn
  - 例外発生時: AppLogger.error（例外メッセージおよび必要に応じてスタックトレース）

## 存在するメソッドと機能（詳細）

### `public static void updateServerInfo(DbSession dbSession, String serverName, String description, String logPath)`
- 機能概要: 指定した `server_name` の `server_description`, `log_path`, `last_log_received`, `updated_at` を更新する。
- 引数:
  - `dbSession`: DB 操作を行うセッションラッパー
  - `serverName`: 更新対象の server_name（NULL/空なら"default"等の扱いは呼び出し側で決定）
  - `description`: サーバー説明文字列
  - `logPath`: ログファイルパス
- 戻り値: なし（更新されなかった場合はログで WARN を出す）
- 実行 SQL（例）:
  ```sql
  UPDATE servers
  SET server_description = ?,
      log_path = ?,
      last_log_received = NOW(),
      updated_at = NOW()
  WHERE server_name = ? COLLATE utf8mb4_unicode_ci
  ```
- エラー処理: SQLException 発生時は AppLogger.error を出力し RuntimeException でラップして投げる。
- ログ: 更新成功時は DEBUG、未更新（0件）の場合は WARN を出力。

---

### `public static void updateServerLastLogReceived(DbSession dbSession, String serverName)`
- 機能概要: 指定サーバの `last_log_received` を現在時刻に更新する。
- 引数:
  - `dbSession`, `serverName`
- 戻り値: なし（内部で更新件数を確認し、0 件なら WARN を出す）
- 実行 SQL:
  ```sql
  UPDATE servers SET last_log_received = NOW() WHERE server_name = ? COLLATE utf8mb4_unicode_ci
  ```
- エラー処理: SQLException はログ記録後 RuntimeException にラップして投げる。

---

### `public static int updateAgentHeartbeat(DbSession dbSession, String registrationId)`
- 機能概要: 指定の `agent_servers.registration_id` の `last_heartbeat` を更新し、`tcp_connection_count` をインクリメントする。
- 引数:
  - `dbSession`, `registrationId`
- 戻り値: 更新件数（int）
- 実行 SQL:
  ```sql
  UPDATE agent_servers SET last_heartbeat = NOW(), tcp_connection_count = tcp_connection_count + 1 WHERE registration_id = ?
  ```
- 例外・エラー: SQLException はログに記録し RuntimeException でラップする。
- ログ: 成功時は DEBUG/INFO、未発見なら WARN。

---

### `public static int updateAgentLogStats(DbSession dbSession, String registrationId, int logCount)`
- 機能概要: 指定エージェントの `total_logs_received` を加算し `last_log_count` を更新する。
- 引数:
  - `dbSession`, `registrationId`, `logCount`
- 戻り値: 更新件数（int）
- 実行 SQL:
  ```sql
  UPDATE agent_servers SET total_logs_received = total_logs_received + ?, last_log_count = ? WHERE registration_id = ?
  ```
- 注意: 大量更新が頻発する場合はバッチ化または別スキーマ（集約テーブル）を検討する。
- エラー処理: SQLException はログ出力後 RuntimeException にラップ。

---

### `public static int updateAccessLogModSecStatus(DbSession dbSession, Long accessLogId, boolean blockedByModSec)`
- 機能概要: `access_log` のレコードに対して `blocked_by_modsec` を設定する。
- 引数:
  - `dbSession`, `accessLogId`, `blockedByModSec`
- 戻り値: 更新件数（int）
- 実行 SQL:
  ```sql
  UPDATE access_log SET blocked_by_modsec = ? WHERE id = ?
  ```
- エラー処理: SQLException をログ記録し RuntimeException をスロー。
- ログ: 成功時は DEBUG/INFO。

---

### `public static int deactivateAgent(DbSession dbSession, String registrationId)`
- 機能概要: 指定エージェントの `status` を `'inactive'` に変更する。
- 引数: `dbSession`, `registrationId`
- 戻り値: 更新件数（int）
- 実行 SQL:
  ```sql
  UPDATE agent_servers SET status = 'inactive' WHERE registration_id = ?
  ```
- エラー処理: SQLException をログで記録して RuntimeException をスロー。
- ログ: 更新成功時に INFO 出力。

---

### `public static void deactivateAllAgents(DbSession dbSession)`
- 機能概要: 全エージェント（`status='active'` のもの）を `inactive` に変更するバッチ処理。
- 引数: `dbSession`
- 戻り値: なし（内部で更新件数をログ出力）
- 実行 SQL:
  ```sql
  UPDATE agent_servers SET status = 'inactive' WHERE status = 'active'
  ```
- エラー処理: SQLException は AppLogger.error によりログ出力し、RuntimeException をスロー。

---

### `public static int updateUrlWhitelistStatus(DbSession dbSession, String serverName, String method, String fullUrl)`
- 機能概要: 指定サーバー / メソッド / フル URL に対して `is_whitelisted` を true に更新する。
- 引数: `dbSession`, `serverName`, `method`, `fullUrl`
- 戻り値: 更新件数（int）
- 実行 SQL:
  ```sql
  UPDATE url_registry
  SET is_whitelisted = true, updated_at = NOW()
  WHERE server_name = ? AND method = ? AND full_url = ?
  ```
- 注意: 一度 `is_whitelisted = true` にした URL を false に戻さない運用（仕様上の制約）についてドキュメントで明示されていること。
- エラー処理: SQLException はログに残し RuntimeException をスロー。

---

### `public static void addDefaultRoleHierarchy(DbSession dbSession, String serverName)`
- 機能概要: グローバルロール（`admin`,`operator`,`viewer`）に対して、サーバー固有ロール（`${serverName}_admin` 等）の ID を `roles.inherited_roles` に追加する。加えて、サーバー固有ロール間での継承も追加する（`operator` は `viewer` を、`admin` は `operator` と `viewer` を継承する）。
- 引数: `dbSession`, `serverName`
- 戻り値: なし
- 実行手順（要点）:
  1. `serverName` が null/空の場合は早期 return。
  2. childRoleNames = `{serverName}_admin`, `{serverName}_operator`, `{serverName}_viewer` を順に取得し、それぞれの id を `roles` テーブルから検索。
  3. グローバル基本ロール `admin/operator/viewer` の `inherited_roles` を読み取り、存在しなければ childRoleId を追加して更新（JSON マージ）。
  4. 追加で、サーバー固有ロール間で継承をセットする:
     - `${server}_operator`.inherited_roles に `${server}_viewer` の id を追加
     - `${server}_admin`.inherited_roles に `${server}_operator` と `${server}_viewer` の id を追加
  5. 更新は Jackson の ObjectMapper で JSON を読み書きし、重複チェックを行う。
- 重要な SQL/ロジック例:
  - ID 取得: `SELECT id FROM roles WHERE role_name = ?`
  - inherited_roles 取得/更新: `SELECT inherited_roles FROM roles WHERE role_name = ?` → parse → update `UPDATE roles SET inherited_roles = ?, updated_at = NOW() WHERE role_name = ?`
- エラー処理・耐障害性:
  - 各 child role の取得に失敗しても処理は継続する（ログ出力して次へ）。
  - JSON のパース/シリアライズエラーは捕捉してログ出力、処理継続。
  - 完全に失敗した場合でも他のロールに対して影響を与えないように個別に try/catch を行う。
- ログ: 追加成功時は INFO、既に存在する場合は DEBUG。

## その他
- トランザクション境界が必要な複数更新がある場合は、呼び出し側が `DbSession` のトランザクション API を利用してまとめて実行すること（このクラスは個別の更新ユーティリティを提供する責務に限定）。
- データ型の取り扱いは null 安全を考慮し、必要なら呼び出し側で事前バリデーションを行うこと。
- ログメッセージに機密情報（パスワード等）を含めないこと。運用環境では Logback 等に切り替え、ログレベル運用を行うこと。

## 変更履歴
- 2.1.0 - 2025-12-31: フォーマット統一（仕様書を統一フォーマットへ変換）
- 2.1.1 - 2026-01-02: 各メソッドの詳細（引数・戻り値・SQL例・エラー処理）を追記

## コミットメッセージ例
- docs(db): DbUpdate のメソッド仕様を詳細化
- fix(db): addDefaultRoleHierarchy の JSON マージ処理説明を明確化
