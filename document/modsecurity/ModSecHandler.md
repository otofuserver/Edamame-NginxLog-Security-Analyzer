# ModSecHandler.java仕様書

## 役割
ModSecurityのログ検出・アラート解析・保存機能を提供するハンドラークラス。
ModSecurityアラートの情報抽出、キュー管理、定期的な照合タスクを担当する。

## 主な機能
- ModSecurityログ行の検出・判定
- アラート情報の抽出（ルールID、メッセージ、重要度、URL等）
- 定期的なアラート照合タスクの管理
- データベースへのアラート保存
- ModSecurityアラートキューとの連携

## 主なメソッド

### 定期的アラート照合機能（v3.0.0新規追加）
- `public static void startPeriodicAlertMatching(ScheduledExecutorService executor, ModSecurityQueue modSecurityQueue, DbService dbService)`
  - **目的**: 5秒間隔での定期的なアラート照合タスクを開始
  - **パラメータ**: 
    - `executor`: スケジューラ実行用のExecutorService
    - `modSecurityQueue`: ModSecurityキュー
    - `dbService`: データベースサービス
  - **動作**: キューに残っているアラートと最近のアクセスログを照合し、一致するものをDBに保存

- `private static void performPeriodicAlertMatching(ModSecurityQueue modSecurityQueue, DbService dbService)`
  - **目的**: 実際の定期的アラート照合処理
  - **動作**: 
    1. キューの状態をチェック
    2. 最近5分以内のアクセスログを取得
    3. ModSecurityブロック未設定のログと照合
    4. 一致したアラートをDBに保存し、access_logを更新

### ログ検出・解析機能
- `public static boolean isModSecurityRawLog(String logLine)`
  - **目的**: ログ行がModSecurityのログかどうかを判定
  - **判定基準**: ログ行に"ModSecurity:"が含まれているかチェック

- `public static Map<String, String> extractModSecInfo(String rawLog)`
  - **目的**: ModSecurityログから詳細情報を抽出
  - **抽出項目**: 
    - ルールID (`[id "数字"]`)
    - メッセージ (`[msg "文字列"]`)
    - データ (`[data "文字列"]`)
    - 重要度 (`[severity "文字列"]`)
    - URL情報（複数パターンで抽出）
  - **戻り値**: 抽出された情報のMap

### URL抽出機能（改善版）
- `private static String extractUrlFromModSecLog(String rawLog)`
  - **目的**: ModSecurityログからURL情報を抽出（優先順位付き）
  - **抽出パターン**（優先度順）:
    1. `request: "GET /path?query HTTP/1.1"`（最も完全）
    2. `[request_uri "/path"]`
    3. `[data "GET /path HTTP/1.1"]`
    4. `[uri "/path"]`
    5. `[file "/path"]`
    6. 生のHTTPリクエスト行
    7. `[matched_var_name "ARGS:param"]`
    8. その他のパターン

- `private static String normalizeModSecUrl(String url)`
  - **目的**: 抽出されたURLを正規化
  - **処理**: 
    - URLデコード
    - スラッシュ補完
    - 無効文字列の除外

- `private static boolean isValidUrl(String url)`
  - **目的**: 抽出されたURLが有効かどうかを判定
  - **判定基準**: 適切なURL形式かチェック

### キュー連携機能
- `public static void processModSecurityAlertToQueue(String rawLog, String serverName, ModSecurityQueue modSecurityQueue)`
  - **目的**: ModSecurityエラーログを解析してキューに追加
  - **処理フロー**:
    1. `extractModSecInfo()`でログ解析
    2. 抽出された情報をキューに追加
    3. 詳細ログ出力

### データベース保存機能
- `public static void saveModSecurityAlertToDatabase(Long accessLogId, ModSecurityQueue.ModSecurityAlert alert, DbService dbService)`
  - **目的**: ModSecurityアラートをデータベースに保存
  - **保存先**: `modsec_alerts`テーブル
  - **保存項目**: 
    - access_log_id（関連するアクセスログID）
    - rule_id（ルールID）
    - message（メッセージ）
    - data_value（データ値）
    - severity（重要度数値）
    - server_name（サーバー名）
    - raw_log（生ログ）
    - detected_at（検出時刻）

### 重要度変換機能
- `public static Integer convertSeverityToInt(String severity)`
  - **目的**: ModSecurity重要度文字列を数値に変換
  - **マッピング**:
    - "emergency" → 0
    - "alert" → 1
    - "critical" → 2
    - "error" → 3
    - "warning" → 4
    - "notice" → 5
    - "info" → 6
    - "debug" → 7
    - 数値文字列 → そのまま変換
    - デフォルト → 2（critical）

## パターンマッチング詳細

### 検出パターン
```java
private static final Pattern[] BLOCK_PATTERNS = {
    Pattern.compile("ModSecurity: Access denied", Pattern.CASE_INSENSITIVE),
    Pattern.compile("ModSecurity.*blocked", Pattern.CASE_INSENSITIVE),
    Pattern.compile("ModSecurity.*denied", Pattern.CASE_INSENSITIVE)
};
```

### 抽出パターン
```java
private static final Pattern RULE_ID_PATTERN = Pattern.compile("\\[id \"(\\d+)\"\\]");
private static final Pattern MSG_PATTERN = Pattern.compile("\\[msg \"([^\"]+)\"\\]");
private static final Pattern DATA_PATTERN = Pattern.compile("\\[data \"([^\"]+)\"\\]");
private static final Pattern SEVERITY_PATTERN = Pattern.compile("\\[severity \"([^\"]+)\"\\]");
```

## 処理フロー（v3.0.0）

### エージェントからのログ処理
1. エージェントがerror.logからModSecurityログを送信
2. `AgentTcpServer`が`isModSecurityRawLog()`で判定
3. `processModSecurityAlertToQueue()`でキューに追加
4. キューで一時保存（時間・URL・サーバー別）

### 定期的アラート照合
1. 5秒間隔で`performPeriodicAlertMatching()`を実行
2. キューにアラートがある場合のみ処理
3. 最近5分以内のアクセスログを取得
4. 時間差とURL一致でアラートを照合
5. 一致したアラートをDBに保存
6. access_logの`blocked_by_modsec`をtrueに更新

### リアルタイム照合
1. アクセスログ保存時に`modSecurityQueue.findMatchingAlerts()`を呼び出し
2. 即座に一致するアラートを検索
3. 見つかった場合は即座にDB保存・フラグ更新

## エラーハンドリング
- 全メソッドで適切な例外処理を実装
- ログ解析失敗時はデフォルト値で続行
- データベース保存失敗時は詳細ログ出力
- URL抽出失敗時は空文字列を返却

## アーキテクチャでの位置づけ
```
NginxLogToMysql
├── ModSecurityQueue (キュー管理)
├── ModSecHandler (アラート処理) ←ここ
└── AgentTcpServer (TCP通信)
```

## 依存関係
- **ModSecurityQueue**: アラートキューとの連携
- **DbService**: データベースアクセス
- **AppLogger**: ログ出力
- **AgentTcpServer**: ModSecurityログの判定・処理要求

## 注意事項
- 全メソッドはstaticメソッドとして実装
- スレッドセーフな実装を保証
- キュー管理の責務は外部（ModSecurityQueue）に委譲
- データベースアクセスはDbServiceを経由

## バージョン情報
- **ModSecHandler.md version**: v1.0.0
- **最終更新**: 2025-08-13
- **主要変更**: 初版作成・v3.0.0アーキテクチャ対応
- ※このファイルを更新した場合は、必ず上記バージョン情報も更新すること
