package com.edamame.security.db;

import com.edamame.security.tools.AppLogger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * データベースセッション管理クラス
 * Connection の自動管理・トランザクション管理・リトライ機能を提供
 */
public class DbSession implements AutoCloseable {
    private Connection connection;
    private final String url;
    private final Properties properties;
    private boolean autoCommit = true;
    private static final int MAX_RETRIES = 5;
    private static final int RETRY_DELAY_MS = 1000;

    /**
     * コンストラクタ
     * @param url データベースURL
     * @param properties 接続プロパティ
     */
    public DbSession(String url, Properties properties) {
        this.url = url;
        this.properties = properties;
    }

    /**
     * データベース接続を取得（遅延初期化）
     * @return Connection インスタンス
     * @throws SQLException 接続エラー
     */
    public Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        return connection;
    }

    /**
     * データベースに接続（リトライ機能付き）
     * @throws SQLException 接続エラー
     */
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

                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("接続リトライが中断されました", ie);
                    }
                }
            }
        }

        throw new SQLException("データベース接続に失敗しました（最大試行回数超過）", lastException);
    }

    /**
     * トランザクション内でDB操作を実行
     * @param operation DB操作（例外をスローする可能性あり）
     * @throws SQLException DB操作エラー
     */
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
            if (e instanceof SQLException) {
                throw (SQLException) e;
            } else {
                throw new SQLException("トランザクション実行中にエラーが発生しました", e);
            }
        } finally {
            try {
                getConnection().setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                AppLogger.error("AutoCommit設定復元に失敗: " + e.getMessage());
            }
        }
    }

    /**
     * トランザクション内でDB操作を実行し、結果を返す
     * @param operation DB操作（戻り値あり）
     * @param <T> 戻り値の型
     * @return 操作結果
     * @throws SQLException DB操作エラー
     */
    public <T> T executeInTransactionWithResult(Function<Connection, T> operation) throws SQLException {
        boolean originalAutoCommit = getConnection().getAutoCommit();
        try {
            getConnection().setAutoCommit(false);
            T result = operation.apply(getConnection());
            getConnection().commit();
            AppLogger.debug("トランザクション完了（戻り値あり）");
            return result;
        } catch (Exception e) {
            try {
                getConnection().rollback();
                AppLogger.warn("トランザクションロールバック実行");
            } catch (SQLException rollbackEx) {
                AppLogger.error("ロールバック失敗: " + rollbackEx.getMessage());
                e.addSuppressed(rollbackEx);
            }
            if (e instanceof SQLException) {
                throw (SQLException) e;
            } else {
                throw new SQLException("トランザクション実行中にエラーが発生しました", e);
            }
        } finally {
            try {
                getConnection().setAutoCommit(originalAutoCommit);
            } catch (SQLException e) {
                AppLogger.error("AutoCommit設定復元に失敗: " + e.getMessage());
            }
        }
    }

    /**
     * 単発のDB操作を実行（自動コミット）
     * @param operation DB操作
     * @throws SQLException DB操作エラー
     */
    public void execute(Consumer<Connection> operation) throws SQLException {
        operation.accept(getConnection());
    }

    /**
     * 単発のDB操作を実行し、結果を返す（自動コミット）
     * @param operation DB操作（戻り値あり）
     * @param <T> 戻り値の型
     * @return 操作結果
     * @throws SQLException DB操作エラー
     */
    public <T> T executeWithResult(Function<Connection, T> operation) throws SQLException {
        return operation.apply(getConnection());
    }

    /**
     * AutoCommitモードを設定
     * @param autoCommit AutoCommitモード
     * @throws SQLException 設定エラー
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        this.autoCommit = autoCommit;
        if (connection != null && !connection.isClosed()) {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * 手動コミット
     * @throws SQLException コミットエラー
     */
    public void commit() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.commit();
        }
    }

    /**
     * 手動ロールバック
     * @throws SQLException ロールバックエラー
     */
    public void rollback() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.rollback();
        }
    }

    /**
     * 接続状態をチェック
     * @return 接続中の場合true
     */
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * リソースのクリーンアップ
     */
    @Override
    public void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                    AppLogger.info("データベース接続を閉じました");
                }
            } catch (SQLException e) {
                AppLogger.error("データベース接続のクローズに失敗: " + e.getMessage());
            }
        }
    }
}
