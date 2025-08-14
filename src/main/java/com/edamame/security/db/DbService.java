package com.edamame.security.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * データベース操作の統合サービスクラス（Static版）
 * グローバルなDbSessionを管理し、staticメソッドでDB操作を提供
 * 1つのDBにのみアクセスすることを前提とした設計
 */
public final class DbService {
    private static DbSession globalSession;
    private static boolean initialized = false;

    // staticクラスのためコンストラクタを非公開
    private DbService() {}

    /**
     * DbServiceを初期化（アプリケーション起動時に1回だけ呼び出し）
     * @param url データベースURL
     * @param properties 接続プロパティ
     * @throws IllegalStateException 既に初期化済みの場合
     */
    public static synchronized void initialize(String url, Properties properties) {
        if (initialized) {
            throw new IllegalStateException("DbService is already initialized");
        }
        globalSession = new DbSession(url, properties);
        initialized = true;
    }

    /**
     * DbServiceが初期化済みかチェック
     * @return 初期化済みの場合true
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * DbServiceをシャットダウン（アプリケーション終了時に呼び出し）
     */
    public static synchronized void shutdown() {
        if (globalSession != null) {
            globalSession.close();
            globalSession = null;
        }
        initialized = false;
    }

    /**
     * 初期化チェック（内部使用）
     * @throws IllegalStateException 未初期化の場合
     */
    private static void checkInitialized() {
        if (!initialized || globalSession == null) {
            throw new IllegalStateException("DbService is not initialized. Call DbService.initialize() first.");
        }
    }

    // ============= SELECT操作 =============

    /**
     * サーバー名でサーバー情報を取得
     * @param serverName サーバー名
     * @return サーバー情報（Optional）
     * @throws SQLException SQL例外
     */
    public static Optional<DbSelect.ServerInfo> selectServerInfoByName(String serverName) throws SQLException {
        checkInitialized();
        return DbSelect.selectServerInfoByName(globalSession, serverName);
    }

    /**
     * サーバー名で存在有無を取得
     * @param serverName サーバー名
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsServerByName(String serverName) throws SQLException {
        checkInitialized();
        return DbSelect.existsServerByName(globalSession, serverName);
    }

    /**
     * 指定registrationIdのpendingなブロック要求リストを取得
     * @param registrationId エージェント登録ID
     * @param limit 最大取得件数
     * @return ブロック要求リスト
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectPendingBlockRequests(String registrationId, int limit) throws SQLException {
        checkInitialized();
        return DbSelect.selectPendingBlockRequests(globalSession, registrationId, limit);
    }

    /**
     * settingsテーブルからホワイトリスト設定を取得
     * @return ホワイトリスト設定Map
     * @throws SQLException SQL例外
     */
    public static Map<String, Object> selectWhitelistSettings() throws SQLException {
        checkInitialized();
        return DbSelect.selectWhitelistSettings(globalSession);
    }

    /**
     * url_registryテーブルに指定のエントリが存在するか判定
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 存在すればtrue
     * @throws SQLException SQL例外
     */
    public static boolean existsUrlRegistryEntry(String serverName, String method, String fullUrl) throws SQLException {
        checkInitialized();
        return DbSelect.existsUrlRegistryEntry(globalSession, serverName, method, fullUrl);
    }

    /**
     * url_registryテーブルからis_whitelistedを取得
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return is_whitelisted値
     * @throws SQLException SQL例外
     */
    public static Boolean selectIsWhitelistedFromUrlRegistry(String serverName, String method, String fullUrl) throws SQLException {
        checkInitialized();
        return DbSelect.selectIsWhitelistedFromUrlRegistry(globalSession, serverName, method, fullUrl);
    }

    /**
     * 最近の指定分数以内のアクセスログを取得（ModSecurity照合用）
     * @param minutes 何分前までのログを取得するか
     * @return アクセスログのリスト
     * @throws SQLException SQL例外
     */
    public static List<Map<String, Object>> selectRecentAccessLogsForModSecMatching(int minutes) throws SQLException {
        checkInitialized();
        return DbSelect.selectRecentAccessLogsForModSecMatching(globalSession, minutes);
    }

    // ============= UPDATE操作（DbUpdateに委譲） =============

    /**
     * サーバー情報を更新
     * @param serverName サーバー��
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void updateServerInfo(String serverName, String description, String logPath) throws SQLException {
        checkInitialized();
        DbUpdate.updateServerInfo(globalSession, serverName, description, logPath);
    }

    /**
     * サーバーの最終ログ受信時刻を更新
     * @param serverName サーバー名
     * @throws SQLException SQL例外
     */
    public static void updateServerLastLogReceived(String serverName) throws SQLException {
        checkInitialized();
        DbUpdate.updateServerLastLogReceived(globalSession, serverName);
    }

    /**
     * エージェントのハートビートを更新
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentHeartbeat(String registrationId) throws SQLException {
        checkInitialized();
        return DbUpdate.updateAgentHeartbeat(globalSession, registrationId);
    }

    /**
     * エージェントのログ処理統計を更新
     * @param registrationId エージェント登録ID
     * @param logCount 処理したログ件数
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAgentLogStats(String registrationId, int logCount) throws SQLException {
        checkInitialized();
        return DbUpdate.updateAgentLogStats(globalSession, registrationId, logCount);
    }

    /**
     * access_logのModSecurityブロック状態を更新
     * @param accessLogId アクセスログID
     * @param blockedByModSec ModSecurityによってブロックされたかどうか
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateAccessLogModSecStatus(Long accessLogId, boolean blockedByModSec) throws SQLException {
        checkInitialized();
        return DbUpdate.updateAccessLogModSecStatus(globalSession, accessLogId, blockedByModSec);
    }

    /**
     * 特定エージェントをinactive状態に変更
     * @param registrationId エージェント登録ID
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int deactivateAgent(String registrationId) throws SQLException {
        checkInitialized();
        return DbUpdate.deactivateAgent(globalSession, registrationId);
    }

    /**
     * 全アクティブエージェントをinactive状態に変更
     * @throws SQLException SQL例外
     */
    public static void deactivateAllAgents() throws SQLException {
        checkInitialized();
        DbUpdate.deactivateAllAgents(globalSession);
    }

    /**
     * URLをホワイトリスト状態に更新
     * @param serverName サーバー名
     * @param method HTTPメソッド
     * @param fullUrl フルURL
     * @return 更新された行数
     * @throws SQLException SQL例外
     */
    public static int updateUrlWhitelistStatus(String serverName, String method, String fullUrl) throws SQLException {
        checkInitialized();
        return DbUpdate.updateUrlWhitelistStatus(globalSession, serverName, method, fullUrl);
    }

    // ============= INSERT/REGISTRY操作（DbRegistry���委譲） =============

    /**
     * サーバー情報を登録または更新
     * @param serverName サーバー名
     * @param description サーバーの説明
     * @param logPath ログファイルパス
     * @throws SQLException SQL例外
     */
    public static void registerOrUpdateServer(String serverName, String description, String logPath) throws SQLException {
        checkInitialized();
        DbRegistry.registerOrUpdateServer(globalSession, serverName, description, logPath);
    }

    /**
     * エージェントサーバーを登録または更新
     * @param serverInfo サーバー情報Map
     * @return 登録ID（成功時）、null（失敗時）
     * @throws SQLException SQL例外
     */
    public static String registerOrUpdateAgent(Map<String, Object> serverInfo) throws SQLException {
        checkInitialized();
        return DbRegistry.registerOrUpdateAgent(globalSession, serverInfo);
    }

    /**
     * access_logテーブルにログを保存
     * @param parsedLog ログ情報Map
     * @return 登録されたaccess_logのID、失敗時はnull
     * @throws SQLException SQL例外
     */
    public static Long insertAccessLog(Map<String, Object> parsedLog) throws SQLException {
        checkInitialized();
        return DbRegistry.insertAccessLog(globalSession, parsedLog);
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
    public static boolean registerUrlRegistryEntry(String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType) throws SQLException {
        checkInitialized();
        return DbRegistry.registerUrlRegistryEntry(globalSession, serverName, method, fullUrl, isWhitelisted, attackType);
    }

    /**
     * modsec_alertsテーブルにModSecurityアラートを保存
     * @param accessLogId access_logテーブルのID
     * @param modSecInfo ModSecurity情報Map
     * @throws SQLException SQL例外
     */
    public static void insertModSecAlert(Long accessLogId, Map<String, Object> modSecInfo) throws SQLException {
        checkInitialized();
        DbRegistry.insertModSecAlert(globalSession, accessLogId, modSecInfo);
    }

    // ============= スキーマ・初期化操作 =============

    /**
     * 全テーブルのスキーマ自動同期
     * @throws SQLException SQL例外
     */
    public static void syncAllTablesSchema() throws SQLException {
        checkInitialized();
        DbSchema.syncAllTablesSchema(globalSession);
    }

    /**
     * 初期データを挿入
     * @param appVersion アプリケーションバージョン
     * @throws SQLException SQL例外
     */
    public static void initializeDefaultData(String appVersion) throws SQLException {
        checkInitialized();
        DbInitialData.initializeDefaultData(globalSession, appVersion);
    }

    /**
     * ログ自動削除バッチ処理
     */
    public static void runLogCleanupBatch() {
        try {
            checkInitialized();
            DbDelete.runLogCleanupBatch(globalSession);
        } catch (Exception e) {
            // 例外は上位でハンドリング、ログ出力のみ
        }
    }

    // ============= トランザクション操作 =============

    /**
     * トランザクション内で複数のDB操作を実行
     * @param operations DB操作のラムダ式
     * @throws SQLException SQL例外
     */
    public static void executeInTransaction(Runnable operations) throws SQLException {
        checkInitialized();
        globalSession.executeInTransaction((Consumer<Connection>) conn -> operations.run());
    }

    // ============= 接続管理 =============

    /**
     * 内部のDbSessionから直接Connectionを取得（既存クラス互換性のため）
     * @return Connection インスタンス
     * @throws SQLException 接続エラー
     */
    public static Connection getConnection() throws SQLException {
        checkInitialized();
        return globalSession.getConnection();
    }

    /**
     * 接続状態をチェック
     * @return 接続中の場合true
     */
    public static boolean isConnected() {
        if (!initialized || globalSession == null) {
            return false;
        }
        return globalSession.isConnected();
    }

    /**
     * AutoCommitモードを設定
     * @param autoCommit AutoCommitモード
     * @throws SQLException 設定エラー
     */
    public static void setAutoCommit(boolean autoCommit) throws SQLException {
        checkInitialized();
        globalSession.setAutoCommit(autoCommit);
    }

    /**
     * 手動コミット
     * @throws SQLException SQL例外
     */
    public static void commit() throws SQLException {
        checkInitialized();
        globalSession.commit();
    }

    /**
     * 手動ロールバック
     * @throws SQLException SQL例外
     */
    public static void rollback() throws SQLException {
        checkInitialized();
        globalSession.rollback();
    }
}
