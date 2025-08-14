# DbInitialData.java 仕様書

## 概要
**バージョン**: v2.1.0  
**更新日**: 2025-01-14

## 役割
- DB初期データ投入ユーティリティ
- settings/roles/users/action_tools/action_rules等の初期レコードを投入
- テーブルが空の場合のみ初期データを投入し、既存データがあればスキップ
- アプリケーション初回起動時やマスターリセット時に使用

## DbSession対応実装（v2.1.0～）
- **DbSessionパターンに完全対応**：Connection管理・例外処理を自動化
- **Connection引数方式は完全廃止**：DbSessionを受け取る方式に統一
- トランザクション管理・ログ出力はDbSessionが担当

## 主要メソッド

### `public static void initializeDefaultData(DbSession dbSession, String appVersion)`
- **機能**: 全初期データテーブルの一括初期化
- **引数**: 
  - `dbSession`: データベースセッション
  - `appVersion`: アプリケーションバージョン（settingsテーブルに記録）
- **実行順序**: 依存関係を考慮した順序で初期化
  1. `settings` (基本設定)
  2. `roles` (ユーザーロール)
  3. `users` (初期管理者、rolesに依存)
  4. `action_tools` (アクションツール)
  5. `action_rules` (アクションルール、action_toolsに依存)
- **エラーハンドリング**: RuntimeException → SQLExceptionでラップして再スロー

## プライベートメソッド詳細

### `initializeSettingsTable(DbSession dbSession, String appVersion)`
**初期設定データ**:
```java
{"app_version", appVersion, "アプリケーションバージョン"}
{"whitelist_enabled", "true", "ホワイトリスト機能有効化"}
{"auto_whitelist_threshold", "100", "自動ホワイトリスト化の閾値"}
{"whitelist_check_interval_minutes", "10", "ホワイトリストチェック間隔（分）"}
{"log_retention_days", "30", "ログ保持日数"}
{"alert_notification_enabled", "true", "アラート通知有効化"}
```

### `initializeRolesTable(DbSession dbSession)`
**初期ロールデータ**:
```java
{"admin", "管理者"}
{"operator", "オペレーター"} 
{"viewer", "閲覧者"}
```

### `initializeUsersTable(DbSession dbSession)`
**初期管理者ユーザー**:
- **ユーザー名**: `admin`
- **メール**: `admin@example.com`
- **パスワード**: `admin123` (BCryptでハッシュ化)
- **ロール**: `admin` (role_id=1)
- **ステータス**: アクティブ (`is_active=TRUE`)

### `initializeActionToolsTable(DbSession dbSession)`
**初期アクションツール**:
```java
{"iptables", "iptables -I INPUT -s {ip} -j DROP", "iptablesによるIP遮断"}
{"fail2ban", "fail2ban-client set nginx-limit-req banip {ip}", "fail2banによるIP遮断"}
{"notification", "curl -X POST -H 'Content-Type: application/json' -d '{\"text\":\"Attack detected from {ip}\"}' {webhook_url}", "Slack/Discord通知"}
```

### `initializeActionRulesTable(DbSession dbSession)`
**初期アクションルール**:
```java
{"high_severity_block", "severity >= 3", action_tool_id=1, active=TRUE, "高レベル攻撃の自動遮断"}
{"repeated_attack_block", "attack_count >= 5", action_tool_id=1, active=TRUE, "繰り返し攻撃の自動遮断"}
{"critical_attack_notify", "severity >= 4", action_tool_id=3, active=TRUE, "重要攻撃の通知"}
```

## 初期化ロジック詳細

### 1. 空テーブル判定
```sql
SELECT COUNT(*) FROM [table_name]
```
- COUNT(*) = 0の場合のみ初期データを挿入
- 既存データがある場合はスキップ

### 2. バッチ挿入
- **PreparedStatement + addBatch()** を使用
- **executeBatch()** で一括挿入による高速化
- SQLインジェクション対策として全パラメータをバインド

### 3. パスワードハッシュ化
```java
BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
String hashedPassword = passwordEncoder.encode("admin123");
```

## DbSessionでの使用方法
```java
// 標準的な使用パターン
try {
    dbSession.execute(conn -> {
        DbInitialData.initializeDefaultData(dbSession, "2.1.0");
    });
} catch (SQLException e) {
    // DbSessionが自動的にエラーハンドリング
}
```

## エラーハンドリング

### 例外処理階層
1. **各プライベートメソッド**: SQLException → RuntimeExceptionでラップ
2. **initializeDefaultData**: RuntimeException → SQLExceptionでラップ
3. **DbSession**: Connection管理・トランザクション管理を自動化

### ログ出力レベル
- **INFO**: 各テーブルの初期データ挿入完了時
- **ERROR**: SQL実行エラー・その他の例外

## セキュリティ対策
- **パスワードハッシュ化**: BCryptPasswordEncoderを使用
- **SQLインジェクション対策**: PreparedStatementによるパラメータバインド
- **デフォルト認証情報**: 初回ログイン後のパスワード変更を強く推奨

## パフォーマンス考慮事項
- **バッチ挿入**: addBatch() + executeBatch()による高速化
- **条件付き実行**: 空テーブルのみ処理でスキップ最適化
- **単一トランザクション**: 各テーブルごとに個別コミット

## 運用・設定

### 初期データカスタマイズ
- **設定値調整**: initializeSettingsTableの配列を変更
- **ユーザー追加**: initializeUsersTableで複数ユーザー対応
- **ツール追加**: initializeActionToolsTableでカスタムツール追加

### セキュリティ設定
- **初期パスワード**: 本番環境では必ず変更
- **ロール権限**: 組織のセキュリティポリシーに合わせて調整
- **通知設定**: Webhook URLや通知テンプレートのカスタマイズ

## 注意事項
- **依存関係**: roles → users、action_tools → action_rulesの順序を厳守
- **新テーブル追加時**: 本クラスへの対応も必須
- **外部キー制約**: 参照整合性を考慮した初期データ設計
- **初期パスワード**: `admin/admin123`は必ず変更すること

## デバッグ・トラブルシューティング

### よくある問題
1. **外部キー制約エラー**: 依存テーブル（roles）が先に初期化されているか確認
2. **重複キーエラー**: 既存データの有無を再確認
3. **BCrypt依存関係**: Spring Security Cryptoライブラリの導入確認

### ログ確認ポイント
- 各テーブルの「初期データ挿入完了」メッセージ
- エラー時の詳細なSQL例外メッセージ
- AppLoggerによる一元的なログ出力

## バージョン履歴
- **v1.0.0**: 初期実装（Connection引数方式）
- **v1.1.0**: DbService統合対応、Connection管理の自動化
- **v2.0.0**: Connection引数方式完全廃止、DbService専用
- **v2.1.0**: **DbSession対応、設定項目統合、アクションルール拡張**

---

## 実装参考

### BCryptパスワードハッシュ化パターン
```java
BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
String hashedPassword = passwordEncoder.encode("admin123");
pstmt.setString(3, hashedPassword);
```

### バッチ挿入パターン
```java
String insertSql = "INSERT INTO table_name (col1, col2) VALUES (?, ?)";
try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
    for (String[] data : initialData) {
        pstmt.setString(1, data[0]);
        pstmt.setString(2, data[1]);
        pstmt.addBatch();
    }
    pstmt.executeBatch();
}
```

### DbSession連携パターン
```java
dbSession.execute(conn -> {
    try {
        // 空テーブル判定 + 初期データ挿入
        boolean isEmpty;
        try (PreparedStatement pstmt = conn.prepareStatement("SELECT COUNT(*) FROM table_name")) {
            ResultSet rs = pstmt.executeQuery();
            isEmpty = rs.next() && rs.getInt(1) == 0;
        }
        if (isEmpty) {
            // バッチ挿入処理
        }
    } catch (SQLException e) {
        AppLogger.error("初期データ挿入エラー: " + e.getMessage());
        throw new RuntimeException(e);
    }
});
```
