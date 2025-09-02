# DbRegistry.java 仕様書

## 概要
**バージョン**: v2.1.1  # roles初期データ投入仕様・履歴更新  
**更新日**: 2025-08-18

## 役割
- データベース登録・新規追加処理専用ユーティリティ
- サーバー情報（serversテーブル）の登録・存在確認・自動登録
- エージェント情報（agent_serversテーブル）の登録・状態管理
- アクセスログ（access_logテーブル）の保存処理
- URL登録（url_registryテーブル）の新規登録
- ModSecurityアラート（modsec_alertsテー��ル）の保存
- **UPDATE系処理はDbUpdate.javaに完全移譲**

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド詳細

### `registerOrUpdateServer(DbSession dbSession, String serverName, String description, String logPath)`
- **機能**: サーバー情報の登録または更新
- **ロジック**:
  1. サーバー名のサニタイズ（null/空文字 → "default"）
  2. 存在確認クエリ（utf8mb4_unicode_ci照合順序対応）
  3. 既存時：DbUpdate.updateServerInfo()を呼び出し
  4. 新規時：serversテ���ブルにINSERT（is_active=TRUE, last_log_received=NOW()）
- **例外処理**: SQLException → RuntimeExceptionでラップ

```sql
-- 存在確認クエリ
SELECT COUNT(*) FROM servers WHERE server_name = ? COLLATE utf8mb4_unicode_ci

-- 新規登録クエリ
INSERT INTO servers (server_name, server_description, log_path, is_active, last_log_received)
VALUES (?, ?, ?, TRUE, NOW())
```

### `registerOrUpdateAgent(DbSession dbSession, Map<String, Object> serverInfo)`
- **機能**: エージェントサーバーの登録または更新
- **戻り値**: 登録成功時は登録ID、失敗時はnull
- **特徴**: ON DUPLICATE KEY UPDATEによる冪等性確保
- **nginx_log_paths処理**: List<String> → JSON文字列変換（Jackson使用、失敗時はカンマ区切り）

```sql
INSERT INTO agent_servers (
    registration_id, agent_name, agent_ip, hostname, os_name, os_version, 
    java_version, nginx_log_paths, iptables_enabled, registered_at, 
    last_heartbeat, status, agent_version
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 'active', ?)
ON DUPLICATE KEY UPDATE
    agent_ip = VALUES(agent_ip),
    hostname = VALUES(hostname),
    os_name = VALUES(os_name),
    os_version = VALUES(os_version),
    java_version = VALUES(java_version),
    nginx_log_paths = VALUES(nginx_log_paths),
    iptables_enabled = VALUES(iptables_enabled),
    last_heartbeat = NOW(),
    status = 'active',
    agent_version = VALUES(agent_version)
```

### `insertAccessLog(DbSession dbSession, Map<String, Object> parsedLog)`
- **機能**: アクセスログの保存
- **戻り値**: 登録されたaccess_logのID（Long）、失敗時はnull
- **フィールドマッピング**: snake_case ↔ camelCaseの両方に対応
- **フォールバック処理**: 各必須フィールドのデフォルト値設定

```java
// フィールドマッピング例
server_name / serverName → サニタイズ済み（null時は"default"）
ip_address / clientIp → サニタイズ済み（null時は"unknown"）
method / httpMethod → サニタイズ済み（null時は"GET"）
full_url / requestUrl → サニタイズ済み（null時は"/"）
status_code / statusCode → デフォルト値0
collectedAt → LocalDateTime/String/現在時刻の自動判定
source_path / sourcePath → 空文字許可
agent_registration_id / agentRegistrationId → NULL許可
```

### `registerUrlRegistryEntry(DbSession, String, String, String, boolean, String)`
- **機能**: URL登録レジストリへの新規URL登録
- **戻り値**: 登録成功時はtrue、失敗時はfalse
- **登録内容**: サーバー名、HTTPメソッド、フルURL、ホワイトリスト判定、攻撃タイプ

```sql
INSERT INTO url_registry (server_name, method, full_url, is_whitelisted, attack_type, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, NOW(), NOW())
```

### `insertModSecAlert(DbSession dbSession, Long accessLogId, Map<String, Object> modSecInfo)`
- **機能**: ModSecurityアラートの保存
- **外部キー**: access_logテーブルとの関連性
- **severity処理**: Integer/String の自動判定（パース失敗時は0）
- **detected_at処理**: LocalDateTime/String の自動判定（パース失敗時は現在時刻）

```sql
INSERT INTO modsec_alerts (
    access_log_id, rule_id, message, data_value, severity,
    server_name, detected_at
) VALUES (?, ?, ?, ?, ?, ?, ?)
```

## プライベートメソッド

### `generateAgentRegistrationId()`
- **機能**: エージェント登録IDの一意生成
- **形式**: `agent-{UUID8桁}`
- **例**: `agent-a1b2c3d4`

## エラーハンドリング

### 例外処理階層
1. **各メ��ッド**: SQLException → RuntimeExceptionでラップ
2. **DbSession**: Connection管理・トランザクション管理を自動化
3. **ログ出力**: AppLogger.info/warn/error/debugによる詳細記録

### ログ出力レベル
- **INFO**: 正常登録完了時
- **WARN**: デフォル���値設定時の警告
- **ERROR**: SQL実行エラー・致命的例外
- **DEBUG**: 詳細デバッグ情報（ID等）

## データ処理特徴

### フォールバック処理
- **必須フィールドのnull安全処理**: デフォルト値による補完
- **フィールド名の柔軟性**: snake_case ↔ camelCase の自動対応
- **型変換の堅牢性**: Integer/String の自動判定・変換

### JSON処理
- **nginx_log_paths**: List<String> → JSON文字列 (Jackson)
- **フォールバック**: JSON化失敗時はカンマ区切り文字列
- **null安全処理**: null値に対する適切なデフォルト処理

### 文字エンコーディング対応
- **utf8mb4_unicode_ci**: サーバー名の照合順序対応
- **マルチバイト文字**: 日本語等の正確な処理

## DbSessionでの使用方法
```java
// 標準的な使用パターン
try {
    // サーバー登録
    DbRegistry.registerOrUpdateServer(dbSession, "web-server-01", "Webサーバー", "/var/log/nginx/");
    
    // アクセスログ保存
    Long accessLogId = DbRegistry.insertAccessLog(dbSession, parsedLogMap);
    
    // ModSecアラート保存
    if (accessLogId != null && modSecInfo != null) {
        DbRegistry.insertModSecAlert(dbSession, accessLogId, modSecInfo);
    }
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

## 連携クラス

### DbUpdate.javaとの役割分担
- **DbRegistry**: INSERT・新規登録処理専門
- **DbUpdate**: UPDATE・既存レコード更新処理専門
- **相互連携**: registerOrUpdateServer内でDbUpdate.updateServerInfo呼び出し

### 依存関係
- **AppLogger**: 統一ログ出力
- **Jackson ObjectMapper**: JSON処理
- **DbSession**: データベースセッション管理

## パフォーマンス考慮事項
- **ON DUPLICATE KEY UPDATE**: エージェント登録の高速冪等処理
- **PreparedStatement**: SQLインジェクション対策＋性能向上
- **Generated Keys**: INSERT後のID取得最適化
- **バッチ処理**: 単一トランザクションでの効率的な処理

## セキュリティ対策
- **SQLインジェクション対策**: 全パラメータのプリペアドステートメントバインド
- **データサニタイズ**: 入力値の適切な検証・変換
- **null安全処理**: NullPointerException の完全回避

## 運用・設定

### 監視ポイント
- **登録成功率**: INFO/ERRORログの比率監視
- **フォールバック頻度**: WARNログによるデータ品質監視
- **性能**: 大量INSERT時のレスポンス時間

### トラブルシューティング
- **外部キー制約エラー**: access_log_id の存在確認
- **重複キー制約エラー**: ON DUPLICATE KEY UPDATE の動作確認
- **JSON処理エラー**: nginx_log_paths のデータ形式確認

## 注意事項
- **新テーブル追加時**: 本クラスへの対応も必須
- **フィールド追加時**: フォールバック処理とマッピング対応
- **外部キー制約**: 削除順序への影響考慮
- **文字エンコーディング**: utf8mb4対応の維持

## バージョン履歴
- **v2.1.1**: roles初期データ投入仕様・addDefaultRoleHierarchy反映

---

## 実装参考

### フォールバック処理パターン
```java
// snake_case ↔ camelCase 対応
String serverName = (String) parsedLog.get("server_name");
if (serverName == null || serverName.trim().isEmpty()) {
    serverName = (String) parsedLog.get("serverName");
    if (serverName == null || serverName.trim().isEmpty()) {
        serverName = "default"; // デフォルト値
        AppLogger.warn("server_name/serverNameがnullのため、デフォルト値を設定");
    }
}
```

### JSON処理パターン
```java
// List<String> → JSON文字列
Object nginxLogPathsObj = serverInfo.get("nginxLogPaths");
String nginxLogPathsStr;
if (nginxLogPathsObj instanceof List) {
    try {
        nginxLogPathsStr = new ObjectMapper().writeValueAsString(pathsList);
    } catch (Exception e) {
        nginxLogPathsStr = String.join(",", pathsList); // フォールバック
    }
}
```

### ON DUPLICATE KEY UPDATE パターン
```sql
INSERT INTO table_name (key_field, data_field) VALUES (?, ?)
ON DUPLICATE KEY UPDATE
    data_field = VALUES(data_field),
    updated_at = NOW()
```