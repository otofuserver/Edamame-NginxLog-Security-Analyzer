# DbSession.java 仕様書

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- データベースセッション管理クラス
- Connection の自動管理・トランザクション管理・リトライ機能を提供
- Connection引数受け渡しの問題を根本的に解決するための基盤クラス
- AutoCloseableインターフェースを実装し、try-with-resourcesでの安全なリソース管理を提供
- **AppLoggerを直接使用**してログ出力の一元化を実現

## 主要メソッド詳細

### `DbSession(String url, Properties properties)`
- **機能**: コンストラクタ
- **引数**: データベースURL、接続プロパティ
- **特徴**: 遅延初期化方式（実際の接続は初回getConnection()時）
- **ログ**: AppLoggerを直接使用（logger引数は廃止）

### `getConnection()`
- **機能**: データベース接続を取得（遅延初期化）
- **戻り値**: Connection インスタンス
- **自動再接続**: 接続が存在しないか切断されている場合は自動で再接続
- **例外**: SQLException（接続エラー）

```java
public Connection getConnection() throws SQLException {
    if (connection == null || connection.isClosed()) {
        connect();
    }
    return connection;
}
```

### `executeInTransaction(Consumer<Connection> operation)`
- **機能**: トランザクション内でDB操作を実行（戻り値なし）
- **自動処理**: AutoCommit制御・コミット・ロールバック
- **例外処理**: ロールバック失敗時はSuppressed例外として追加

```java
public void executeInTransaction(Consumer<Connection> operation) throws SQLException {
    boolean originalAutoCommit = getConnection().getAutoCommit();
    try {
        getConnection().setAutoCommit(false);
        operation.accept(getConnection());
        getConnection().commit();
        AppLogger.debug("トランザクション完了");
    } catch (Exception e) {
        try {
            getConnection().rollback();
            AppLogger.warn("トランザクションロールバック実行");
        } catch (SQLException rollbackEx) {
            AppLogger.error("ロールバック失敗: " + rollbackEx.getMessage());
            e.addSuppressed(rollbackEx);
        }
        // 例外の再スロー処理
    } finally {
        // AutoCommit設定復元
    }
}
```

### `execute(Consumer<Connection> operation)`
- **機能**: 単発のDB操作を実行（自動コミット）
- **用途**: 単純なINSERT/UPDATE/DELETE操作
- **特徴**: トランザクション管理なし

```java
public void execute(Consumer<Connection> operation) throws SQLException {
    operation.accept(getConnection());
}
```

### `executeWithResult(Function<Connection, T> operation)`
- **機能**: 単発のDB操作を実行し、結果を返す（自動コミット）
- **戻り値**: ジェネリック型T（操作結果）
- **用途**: SELECT操作・戻り値が必要な処理

```java
public <T> T executeWithResult(Function<Connection, T> operation) throws SQLException {
    return operation.apply(getConnection());
}
```

### その他のメソッド
- `setAutoCommit(boolean autoCommit)`: AutoCommitモード設定
- `commit()`: 手動コミット
- `rollback()`: 手動ロールバック
- `isConnected()`: 接続状態チェック
- `close()`: リソースクリーンアップ（AutoCloseable実装）

## 接続管理・リトライ機能

### `connect()` - プライベートメソッド
- **機能**: データベースに接続（リトライ機能付き）
- **最大リトライ**: 5回（MAX_RETRIES定数）
- **遅延戦略**: リニア増加（1秒→2秒→3秒...）
- **中断処理**: InterruptedExceptionをSQLExceptionでラップ

```java
private void connect() throws SQLException {
    SQLException lastException = null;
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        try {
            connection = DriverManager.getConnection(url, properties);
            connection.setAutoCommit(autoCommit);
            AppLogger.info("データベース接続成功 (試行回数: " + attempt + ")");
            return;
        } catch (SQLException e) {
            lastException = e;
            AppLogger.warn("データベース接続失敗 (試行 " + attempt + "/" + MAX_RETRIES + "): " + e.getMessage());
            // リトライ処理
        }
    }
    throw new SQLException("データベース接続に失敗しました（最大試行回数超過）", lastException);
}
```

## 接続の有効性チェックと再接続（新機能）

### `ensureConnected()`
- **機能**: 現在の `Connection` が有効であるかを検査し、無効な場合は再接続を行う。
- **用途**: 長時間稼働するアプリケーションで MySQL の再起動やネットワーク断による接続切断を検知して自動回復するために使用する。
- **実装ポイント**:
  - `connection == null` または `connection.isClosed()` の場合は `connect()` を呼び出す。
  - `connection.isValid(2)` で接続死活確認する（ドライバが未実装の場合は `SELECT 1` を実行して確認する）。
  - 無効と判定された場合は `connection.close()` を試みた後に `connect()` を呼び再接続を行う。
  - `connect()` は既存のリトライロジック（最大リトライ回数、バックオフ）を利用するため、短時間の DB 再起動にも自動復旧する。
- **例外**: 再接続に失敗した場合は `SQLException` を投げる。

```java
// 使用例: DbService 経由で接続を取得する前に自動で呼ばれることを想定
DbSession session = new DbSession(url, props);
session.ensureConnected(); // 必要に応じて再接続
Connection conn = session.getConnection();
```

### 導入の効果
- MySQL の再起動や一時的なネットワーク障害で `Connection` オブジェクトが無効になっても、呼び出し側は明示的な再接続処理を行う必要がなくなる。
- `ensureConnected()` により、`DbService.getConnection()` を呼ぶだけで自動的に安全な接続が保証されるため、アプリケーション全体の耐障害性が向上する。

## 設定項目（定数）
- **MAX_RETRIES**: 最大リトライ回数（5回）
- **RETRY_DELAY_MS**: 初回リトライ遅延時間（1000ms）
- **autoCommit**: AutoCommitモード（デフォルト: true）

## トランザクション処理の詳細フロー
1. **準備**: 現在のAutoCommit設定を保存
2. **開始**: AutoCommitをfalseに設定
3. **実行**: 渡された操作（Consumer）を実行
4. **成功時**: commit()実行 → DEBUGログ出力
5. **失敗時**: rollback()実行 → WARNログ出力 → 元の例外を再スロー
6. **最終処理**: AutoCommit設定を元の値に復元

## エラーハンドリング

### 例外処理の階層
1. **接続エラー**: 最大リトライ回数超過時、詳細情報付きSQLException
2. **トランザクションエラー**: rollback失敗時はSuppressed例外として追加
3. **AutoCommit復元エラー**: finally節で失敗時はERRORログ出力
4. **一般例外**: SQLExceptionでない例外をSQLExceptionでラップ

### ログ出力レベル
- **INFO**: 接続成功・接続クローズ
- **DEBUG**: トランザクション完了
- **WARN**: 接続失敗（リトライ中）・ロールバック実行
- **ERROR**: ロールバック失敗・AutoCommit復元失敗・接続クローズ失敗

## DbSessionの使用パターン

### 基本的な単発操作
```java
try (DbSession dbSession = new DbSession(url, properties)) {
    // 戻り値なしの操作
    dbSession.execute(conn -> {
        DbRegistry.registerOrUpdateServer(dbSession, serverName, description, logPath);
    });
    
    // 戻り値ありの操作
    Optional<ServerInfo> serverInfo = dbSession.executeWithResult(conn -> {
        return DbSelect.selectServerInfoByName(dbSession, serverName);
    });
} catch (SQLException e) {
    AppLogger.error("DB操作エラー: " + e.getMessage());
}
```

### トランザクション操作
```java
try (DbSession dbSession = new DbSession(url, properties)) {
    dbSession.executeInTransaction(conn -> {
        // 複数のDB操作を1つのトランザクションで実行
        DbRegistry.insertAccessLog(dbSession, parsedLog);
        DbUpdate.updateServerLastLogReceived(dbSession, serverName);
        DbRegistry.insertModSecAlert(dbSession, accessLogId, modSecInfo);
        // 全て成功時に自動コミット、例外発生時は自動ロールバック
    });
} catch (SQLException e) {
    AppLogger.error("トランザクション処理エラー: " + e.getMessage());
}
```

### 手動トランザクション制御
```java
try (DbSession dbSession = new DbSession(url, properties)) {
    dbSession.setAutoCommit(false);
    try {
        // 手動でDB操作
        dbSession.execute(conn -> { /* 操作1 */ });
        dbSession.execute(conn -> { /* 操作2 */ });
        
        // 手動コミット
        dbSession.commit();
    } catch (Exception e) {
        // 手動ロールバック
        dbSession.rollback();
        throw e;
    }
} catch (SQLException e) {
    AppLogger.error("手動トランザクション処理エラー: " + e.getMessage());
}
```

## 他のDbクラスとの連携
DbSessionは以下のクラスで共通的に使用されます：

- **DbRegistry**: INSERT・新規登録処理
- **DbSelect**: SELECT・検索処理
- **DbUpdate**: UPDATE・更新処理
- **DbDelete**: DELETE・削除処理
- **DbSchema**: スキーマ管理・テーブル作成
- **DbInitialData**: 初期データ投入

## パフォーマンス考慮事項
- **遅延初期化**: 実際に必要になるまで接続を作成しない
- **接続再利用**: 同一セッション内で接続インスタンスを再利用
- **リトライ戦略**: リニア増加による段階的遅延
- **リソース管理**: AutoCloseableによる確実なリソース解放

## セキュリティ対策
- **接続情報保護**: Properties経由での安全な接続情報管理
- **SQLインジェクション**: PreparedStatementと組み合わせて使用
- **リソースリーク防止**: try-with-resourcesでの確実なクローズ
- **例外情報制限**: 接続エラー時の詳細情報を適切にログレベルで制御

## 注意事項・制限事項
- **スレッドセーフ性**: 同一インスタンスを複数スレッドで共有しないこと
- **長期保持禁止**: 長時間のセッション保持はコネクションタイムアウトの原因
- **例外処理必須**: SQL例外は呼び出し元で適切にハンドリングすること
- **リソース管理**: try-with-resourcesでの使用を強く推奨

## バージョン履歴
- **v1.0.0**: 初期実装（BiConsumer logger引数あり）
- **v2.0.0**: Connection管理機能強化
- **v2.1.0**: **AppLogger直接使用、logger引数廃止、executeWithResultメソッド追加**

---

## 実装参考

### Consumer<Connection>使用パターン
```java
dbSession.execute(conn -> {
    try (var pstmt = conn.prepareStatement("INSERT INTO table VALUES (?)")) {
        pstmt.setString(1, value);
        pstmt.executeUpdate();
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
});
```

### Function<Connection, T>使用パターン
```java
List<String> results = dbSession.executeWithResult(conn -> {
    List<String> list = new ArrayList<>();
    try (var pstmt = conn.prepareStatement("SELECT name FROM table")) {
        var rs = pstmt.executeQuery();
        while (rs.next()) {
            list.add(rs.getString("name"));
        }
    } catch (SQLException e) {
        throw new RuntimeException(e);
    }
    return list;
});
```

### エラーハンドリングパターン
```java
try (DbSession dbSession = new DbSession(url, properties)) {
    dbSession.executeInTransaction(conn -> {
        // DB操作
        if (error_condition) {
            throw new RuntimeException("ビジネスロジックエラー");
        }
    });
} catch (SQLException e) {
    AppLogger.error("SQL実行エラー: " + e.getMessage());
    // エラー処理
} catch (Exception e) {
    AppLogger.error("予期しないエラー: " + e.getMessage());
    // エラー処理
}
```
