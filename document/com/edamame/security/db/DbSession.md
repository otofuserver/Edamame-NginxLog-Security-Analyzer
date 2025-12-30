# DbSession

対象: `src/main/java/com/edamame/security/db/DbSession.java`

## 概要
- JDBC の Connection 管理（接続、再接続、トランザクション管理）を担うユーティリティクラス。
- 接続時のリトライや自動再接続、トランザクション実行ラッパーなどを提供する。

## 主な機能
- Connection の確立（最大リトライ回数: 5）
- トランザクションの実行（executeInTransaction）と自動コミットの制御
- 単発/結果付き DB 操作ユーティリティ（execute, executeWithResult）
- 接続状態チェックと再接続（ensureConnected）

## 挙動
- `connect()` は最大 `MAX_RETRIES`（ソースでは 5）回の再試行を行い、失敗時は SQLException をスローする。
- `ensureConnected()` は接続の有効性をチェックし、無効な場合は再接続を試みる。
- `executeInTransaction` はオペレーションを実行し、例外時は rollback を行った上で SQLException をスローする。

## 細かい指定された仕様
- リトライ待ち時間は `RETRY_DELAY_MS`（ソースでは 1000ms）に基づき、試行回数に応じてエクスポネンシャル的に待機する。
- 接続チェックは `isValid` を利用するが、ドライバーによって未実装の場合は代替クエリでチェックする。
- close() は Connection を安全にクローズし、ログ出力を行う。

## メソッド一覧と機能（主なもの）
- `public DbSession(String url, Properties properties)`
  - コンストラクタ。接続 URL と接続プロパティを受け取る。

- `public Connection getConnection() throws SQLException`
  - Connection を返す。未接続時は connect() を呼び再接続する。

- `public <T> T executeWithResult(Function<Connection, T> operation) throws SQLException`
  - DB 操作を実行し結果を返すユーティリティ。

- `public void executeInTransaction(Consumer<Connection> operation) throws SQLException`
  - トランザクション内で操作を実行し成功時は commit、失敗時は rollback を行う。

- `public synchronized void ensureConnected() throws SQLException`
  - 接続の有効性を確認し、必要なら再接続を行う。

- `public void close()`
  - Connection を閉じる（AutoCloseable 実装）。

## その他
- 接続情報（パスワード等）は取り扱いに注意し、ログに出力しないこと。
- 大規模環境では接続プールの導入（HikariCP 等）を検討すること。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成（ソースに基づく）

## コミットメッセージ例
- docs(db): DbSession の仕様書を追加

