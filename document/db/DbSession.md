# DbSession.java 仕様書

## 役割
- データベースセッション管理クラス。
- Connection の自動管理・トランザクション管理・リトライ機能を提供。
- Connection引数受け渡しの問題を根本的に解決するための基盤クラス。
- AutoCloseableインターフェースを実装し、try-with-resourcesでの安全なリソース管理を提供。

## 主なメソッド
- `public DbSession(String url, Properties properties, BiConsumer<String, String> logger)`
  - コンストラクタ。データベースURL、接続プロパティ、ログ出力関数を設定。
- `public Connection getConnection() throws SQLException`
  - データベース接続を取得（遅延初期化）。接続が存在しないか切断されている場合は自動で再接続。
- `public void executeInTransaction(Consumer<Connection> operation) throws SQLException`
  - トランザクション内でDB操作を実行。自動でコミット/ロールバック処理を行う。
- `public <T> T executeInTransaction(Function<Connection, T> operation) throws SQLException`
  - トランザクション内でDB操作を実行し、結果を返す。自動でコミット/ロールバック処理を行う。
- `public void execute(Consumer<Connection> operation) throws SQLException`
  - 単発のDB操作を実行（自動コミット）。
- `public <T> T execute(Function<Connection, T> operation) throws SQLException`
  - 単発のDB操作を実行し、結果を返す（自動コミット）。
- `public void setAutoCommit(boolean autoCommit) throws SQLException`
  - AutoCommitモードを設定。接続済みの場合は即座に反映。
- `public void commit() throws SQLException`
  - 手動コミット。接続が有効な場合のみ実行。
- `public void rollback() throws SQLException`
  - 手動ロールバック。接続が有効な場合のみ実行。
- `public boolean isConnected()`
  - 接続状態をチェック。接続中の場合true、切断されている場合false。
- `public void close()`
  - リソースのクリーンアップ。AutoCloseableインターフェースの実装。

## 接続管理・リトライ機能
- `private void connect() throws SQLException`
  - データベースに接続（リトライ機能付き）。最大5回まで自動リトライ。
  - 段階的な遅延（1秒→2秒→3秒...）でリトライ間隔を調整。
  - 接続失敗時は詳細なエラーログを出力し、最終的にSQLExceptionをスロー。

## ロジック
- **遅延初期化**: getConnection()呼び出し時に初めて実際の接続を確立。
- **自動再接続**: 接続が切断されている場合、getConnection()で自動的に再接続を試行。
- **リトライ機能**: 接続失敗時に最大5回まで自動リトライ（指数バックオフ式の遅延）。
- **トランザクション管理**: executeInTransaction()内で自動的にAutoCommitをfalseに設定し、処理完了後にcommit、例外発生時にrollbackを実行。
- **例外処理**: SQLException以外の例外もキャッチし、適切にSQLExceptionでラップして再スロー。
- **リソース管理**: close()でConnection の安全なクローズ処理を実行。
- **ログ出力**: 接続成功/失敗、トランザクション状態、エラー情報をINFO/WARN/ERRORレベルで出力。

## 設定項目
- **MAX_RETRIES**: 最大リトライ回数（デフォルト: 5回）
- **RETRY_DELAY_MS**: 初回リトライ遅延時間（デフォルト: 1000ms）
- **autoCommit**: AutoCommitモード（デフォルト: true）

## トランザクション処理の詳細
1. **開始**: 現在のAutoCommit設定を保存し、falseに変更
2. **実行**: 渡された操作（Consumer/Function）を実行
3. **成功**: commit()を実行し、DEBUGログを出力
4. **失敗**: rollback()を実行し、WARNログを出力、元の例外を再スロー
5. **最終**: AutoCommit設定を元の値に復元

## エラーハンドリング
- **接続エラー**: 最大リトライ回数を超えた場合、詳細なエラー情報とともにSQLExceptionをスロー
- **トランザクションエラー**: rollback失敗時は元の例外にSuppressed例外として追加
- **AutoCommit復元エラー**: finally節でのAutoCommit復元失敗時はERRORログを出力
- **ロールバックエラー**: ロールバック失敗時はERRORログを出力し、元の例外に追加

## 注意事項
- Connection の生成・管理は本クラスに完全に委譲すること。
- トランザクション処理は必ずexecuteInTransaction()を使用すること。
- 例外処理は呼び出し元で適切にハンドリングすること。
- リソースリークを防ぐため、try-with-resourcesでの使用を推奨。
- 同一インスタンスを複数スレッドで共有しないこと（スレッドセーフではない）。

## 使用例
```java
// 基本的な使用方法
try (DbSession session = new DbSession(url, props, logger)) {
    // 単発操作
    session.execute(conn -> {
        // DB操作
    });
    
    // トランザクション操作
    session.executeInTransaction(conn -> {
        // 複数のDB操作
        DbRegistry.insertAccessLog(conn, logData, logger);
        DbUpdate.updateServerLastLogReceived(conn, serverName, logger);
    });
}

// 戻り値を受け取る場合
try (DbSession session = new DbSession(url, props, logger)) {
    Optional<ServerInfo> info = session.execute(conn -> 
        DbSelect.selectServerInfoByName(conn, serverName)
    );
    
    String result = session.executeInTransaction(conn -> {
        // 処理して結果を返す
        return "success";
    });
}
```

---

### 参考：実装例
- DbSessionはpublic classとして実装、AutoCloseableインターフェースを実装。
- Connection管理・リトライ・トランザクション処理をすべて内包。
- ログ出力はBiConsumer<String, String>でINFO/WARN/ERROR/DEBUGレベルを明示。
- 例外時はcatchでエラーログを出力し、適切にSQLExceptionをスロー。

---
