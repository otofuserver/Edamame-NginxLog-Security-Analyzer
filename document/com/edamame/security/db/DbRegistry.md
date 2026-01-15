# DbRegistry

対象: `src/main/java/com/edamame/security/db/DbRegistry.java`

## 概要
- INSERT / 登録系処理を集約するユーティリティ。サーバー・エージェント登録やアクセスログ登録、ModSecurity アラート保存、URL 登録などを提供する。
- URLレジストリについては初回登録だけでなく、`DbUpdate.updateUrlRegistryLatest` と組み合わせることで最新アクセス時刻・HTTPステータス・ModSec判定を追跡できる。

## 主な機能
- サーバー登録/更新（`registerOrUpdateServer`）
- エージェント登録/更新（`registerOrUpdateAgent`）
- アクセスログ挿入（`insertAccessLog`）
- URL レジストリ登録（`registerUrlRegistryEntry`）
- URL レジストリ最新情報更新（`updateUrlRegistryLatest`）
- ModSecurity アラート保存（`insertModSecAlert`）

## 挙動
- INSERT 時は PreparedStatement を利用して SQL インジェクションを防止する。
- `registerOrUpdateAgent` は重複キー時は UPDATE を行う構文（ON DUPLICATE KEY UPDATE）で設計され、登録ID を UUID ベースで生成して返す。
- `insertAccessLog` は複数のフィールド名（snake_case / camelCase）のフォールバック対応、型の安全な変換（LocalDateTime/Timestamp/String）を実施する。
- `updateUrlRegistryLatest` は既存行のみを対象に `latest_access_time` / `latest_status_code` / `latest_blocked_by_modsec` を更新する。タイムスタンプ未提供時は現在時刻で補完し、MySQL照合順序（`utf8mb4_unicode_ci`）を明示。

## 主なメソッド
- `public static void registerOrUpdateServer(DbSession dbSession, String serverName, String description, String logPath)`
- `public static String registerOrUpdateAgent(DbSession dbSession, Map<String,Object> serverInfo)`
- `public static Long insertAccessLog(DbSession dbSession, Map<String,Object> parsedLog)`
- `public static boolean registerUrlRegistryEntry(DbSession dbSession, String serverName, String method, String fullUrl, boolean isWhitelisted, String attackType, Timestamp latestAccessTime, Integer latestStatusCode, Boolean latestBlockedByModsec)`
- `public static void updateUrlRegistryLatest(DbSession dbSession, String serverName, String method, String fullUrl, Timestamp latestAccessTime, Integer latestStatusCode, Boolean latestBlockedByModsec)`
- `public static void insertModSecAlert(DbSession dbSession, Long accessLogId, Map<String,Object> modSecInfo)`

## 変更履歴
- 2.1.1 - 2026-01-15: `updateUrlRegistryLatest` を追加し、最新アクセス時刻/ステータス/ModSec判定を既存URLにも反映できるよう明記
- 2.1.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(db): DbRegistry の仕様書を更新（最新アクセスメタデータ追跡を追加）
