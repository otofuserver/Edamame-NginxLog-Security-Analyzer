package com.edamame.security.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * データベース操作の統合サービスクラス
 * DbSessionを内部で管理し、従来のConnection引数なしでDB操��を提供
 */
public class DbService implements AutoCloseable {
    private final DbSession session;

    /**
     * コンストラクタ
     * @param url データベースURL
     * @param properties 接続プロパティ
     */
    public DbService(String url, Properties properties) {
        this.session = new DbSession(url, properties);
    }

    // ============= SELECT操作 =============

    /**
     * サーバー名でサーバー情報を取得
     * @param serverName サーバー名
     * @return サーバー情報（Optional）
     * @throws SQLException SQL例外
     */
    public Optional<DbSelect.ServerInfo> selectServerInfoByName(String serverName) throws SQLException {
        return DbSelect.selectServerInfoByName(this, serverName);
    }

    /**
     * サーバー名で存在有無を取得
     * @param serverName サーバー名
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public boolean existsServerByName(String serverName) throws SQLException {
        return DbSelect.existsServerByName(this, serverName);
    }

    /**
     * 指定registrationIdのpendingなブロック要求リストを取得
     * @param registrationId エージェント登録ID
     * @param limit 最大取得件数
     * @return ブロック要求リスト
     * @throws SQLException SQL例外
     */
    public List<Map<String, Object>> selectPendingBlockRequests(String registrationId, int limit) throws SQLException {
        return DbSelect.selectPendingBlockRequests(this, registrationId, limit);
    }

    /**
     * settingsテーブルからホワイトリスト設定を取得
     * @return ホワイトリスト設定Map
     * @throws SQLException SQL例外
     */
    public Map<String, Object> selectWhitelistSettings() throws SQLException {
        return DbSelect.selectWhitelistSettings(this);
    }

    /**
     * url_registryテーブルに指定のエントリが存在するか判定
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public boolean existsUrlRegistryEntry(String serverName, String method, String fullUrl) throws SQLException {
        return DbSelect.existsUrlRegistryEntry(this, serverName, method, fullUrl);
    }

    /**
     * url_registryテーブルからis_whitelistedを取得
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return is_whitelisted値
     * @throws SQLException SQL例外
     */
    public Boolean selectIsWhitelistedFromUrlRegistry(String serverName, String method, String fullUrl) throws SQLException {
        return DbSelect.selectIsWhitelistedFromUrlRegistry(this, serverName, method, fullUrl);
    }

    // ============= UPDATE操作（DbUpdateに委譲） =============

    /**
     * サーバー情報を更新
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public void updateServerInfo(String serverName, String description, String logPath) throws SQLException {
        DbUpdate.updateServerInfo(this, serverName, description, logPath);
    }

    /**
     * サーバーの最終ログ受信時刻を更新
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public void updateServerLastLogReceived(String serverName) throws SQLException {
        DbUpdate.updateServerLastLogReceived(this, serverName);
    }

    /**
     * エージェントのハートビートを更新
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public int updateAgentHeartbeat(String registrationId) throws SQLException {
        return DbUpdate.updateAgentHeartbeat(this, registrationId);
    }

    /**
     * エージェントのログ処理統計を更新
     * @param registrationId エージェント登録ID
     * @param logCount 処理したログ件数
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public int updateAgentLogStats(String registrationId, int logCount) throws SQLException {
        return DbUpdate.updateAgentLogStats(this, registrationId, logCount);
    }

    /**
     * 特定エージェントをinactive状態に変更
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public int deactivateAgent(String registrationId) throws SQLException {
        return DbUpdate.deactivateAgent(this, registrationId);
    }

    /**
     * 全アクティブエージェントをinactive状態に変更
     * @throws SQLException SQL例外
     */
    public void deactivateAllAgents() throws SQLException {
        DbUpdate.deactivateAllAgents(this);
    }

    /**
     * URLをホワイトリスト状態に更新
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public int updateUrlWhitelistStatus(String serverName, String method, String fullUrl) throws SQLException {
        return DbUpdate.updateUrlWhitelistStatus(this, serverName, method, fullUrl);
    }

    // ============= INSERT/REGISTRY操作（DbRegistryに委譲） =============

    /**
     * サーバー情報を登録または更新
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public void registerOrUpdateServer(String serverName, String description, String logPath) throws SQLException {
        DbRegistry.registerOrUpdateServer(this, serverName, description, logPath);
    }

    /**
     * エージェントサーバーを登録または更新
     * @param serverInfo サーバー情報Map
     * @return 登録ID（成功時）、null（失敗時）
     * @throws SQLException SQL例外
     */
    public String registerOrUpdateAgent(Map<String, Object> serverInfo) throws SQLException {
        return DbRegistry.registerOrUpdateAgent(this, serverInfo);
    }

    /**
     * access_logテーブルにログを保存
     * @param parsedLog ログ情報Map
     * @return 登録されたaccess_logのID、失敗時はnull
     * @throws SQLException SQL例外
     */
    public Long insertAccessLog(Map<String, Object> parsedLog) throws SQLException {
        return DbRegistry.insertAccessLog(this, parsedLog);
    }

    /**
     * url_registryテーブルに新規URLを登録
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @param isWhitelisted ホワイトリスト判定
     * @param attackType 攻撃タイプ
     * @return 登録成功時はtrue
     * @throws SQLException SQL例外
     */
    public boolean registerUrlRegistryEntry(String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType) throws SQLException {
        return DbRegistry.registerUrlRegistryEntry(this, serverName, method, fullUrl, isWhitelisted, attackType);
    }

    /**
     * modsec_alertsテーブルにModSecurityアラートを保存
     * @param accessLogId access_logテーブルのID
     * @param modSecInfo ModSecurity情報Map
     * @throws SQLException SQL例外
     */
    public void insertModSecAlert(Long accessLogId, Map<String, Object> modSecInfo) throws SQLException {
        DbRegistry.insertModSecAlert(this, accessLogId, modSecInfo);
    }

    // ============= スキーマ・初期化操作 =============

    /**
     * 全テーブルのスキーマ自動同期
     * @throws SQLException SQL例外
     */
    public void syncAllTablesSchema() throws SQLException {
        DbSchema.syncAllTablesSchema(this);
    }

    /**
     * 初期データを挿入
     * @param appVersion アプリケーションバージョン
     * @throws SQLException SQL例外
     */
    public void initializeDefaultData(String appVersion) throws SQLException {
        DbInitialData.initializeDefaultData(this, appVersion);
    }

    /**
     * ログ自動削除バッチ処理
     */
    public void runLogCleanupBatch() {
        try {
            DbDelete.runLogCleanupBatch(this);
        } finally {
            // 例外は上位でハンドリング、catch不要
        }
    }

    // ============= トランザクション操作 =============

    /**
     * トランザクション内で複数のDB操作を実行
     * @param operations DB操作のリスト
     * @throws SQLException SQL例外
     */
    public void executeInTransaction(Runnable operations) throws SQLException {
        session.executeInTransaction((Consumer<Connection>) conn -> operations.run());
    }



    // ============= 接続管理 =============

    /**
     * 内部のDbSessionから直接Connectionを取得（既存クラス互換性のため）
     * @return Connection インスタンス
     * @throws SQLException 接続エラー
     */
    public Connection getConnection() throws SQLException {
        return session.getConnection();
    }

    /**
     * 接続状態をチェック
     * @return 接続中の場合true
     */
    public boolean isConnected() {
        return session.isConnected();
    }

    /**
     * 手動コミット
     * @throws SQLException コミットエラー
     */
    public void commit() throws SQLException {
        session.commit();
    }

    /**
     * 手動ロールバック
     * @throws SQLException ロールバックエラー
     */
    public void rollback() throws SQLException {
        session.rollback();
    }

    /**
     * AutoCommitモードを設定
     * @param autoCommit AutoCommitモード
     * @throws SQLException 設定エラー
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        session.setAutoCommit(autoCommit);
    }

    /**
     * 内部のDbSessionを取得（Db*クラスからの呼び出し用）
     * @return DbSession インスタンス
     */
    public DbSession getSession() {
        return session;
    }
    

    /**
     * リソースのクリーンアップ
     */
    @Override
    public void close() {
        session.close();
    }
}
