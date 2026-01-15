# DbService
- docs(db): DbService の仕様書を追加
## コミットメッセージ例

- 2.1.1 - 2026-01-15: `updateUrlRegistryLatest` ラッパーを追加した委譲APIを明記
- 2.1.0 - 2025-12-31: ドキュメント作成
## 変更履歴

- `public static Connection getConnection()` / `public static boolean isConnected()`
- `public static void executeInTransaction(Runnable operations)`
- `public static void syncAllTablesSchema()` / `public static void initializeDefaultData(String appVersion)`
- `public static Optional<DbSelect.ServerInfo> selectServerInfoByName(String serverName)` ほか多数の委譲メソッド
- `public static synchronized void shutdown()`
- `public static synchronized void initialize(String url, Properties properties)`
## 主なメソッド

- `getConnection()` は内部 session の再接続を保証して Connection を返す（既存互換 API を維持）。
- `executeInTransaction` は Runnable を受け取り内部で Connection を渡して一括実行するシンプルなトランザクションラッパーを提供する。
- 単一 DB（マルチ DB 非対応）を前提とした設計。
## 細かい指定された仕様

- 実際の DB ロジックは責務毎に `DbSelect`, `DbUpdate`, `DbRegistry`, `DbSchema`, `DbInitialData` などへ委譲される。
- 各操作は初期化チェック（`checkInitialized()`）を行い、未初期化の場合は IllegalStateException を投げる。
- アプリ起動時に一度 `initialize(url, properties)` を呼び出して内部 `DbSession` を構築する。
## 挙動

- 直接 Connection を取り出す互換 API（`getConnection`）および接続状態確認 (`isConnected`) を提供
- トランザクション実行ユーティリティ（`executeInTransaction`）
- 各種 SELECT/UPDATE/INSERT 操作の委譲（`DbSelect`, `DbUpdate`, `DbRegistry`, `DbSchema`, `DbInitialData` など）
- URL レジストリの最新メタデータ更新を `updateUrlRegistryLatest` で委譲（既存URLの最終アクセスを同期）
- `DbSession` の初期化・シャットダウン管理（`initialize`, `shutdown`）
## 主な機能

- アプリケーション全体で使う静的な DB 操作入口。内部で単一の `DbSession` を保持し、SELECT/UPDATE/INSERT/スキーマ同期/トランザクション等を呼び出し側へ提供するファサード。
## 概要

対象: `src/main/java/com/edamame/security/db/DbService.java`
