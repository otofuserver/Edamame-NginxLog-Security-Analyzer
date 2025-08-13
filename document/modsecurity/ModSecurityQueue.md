# ModSecurityQueue.java仕様書

## 役割
ModSecurityアラートを一時的に保存し、時間・URL一致による関連付けを行うキュー管理クラス。
アラートの追加、検索、期限切れクリーンアップ機能を提供する。

## 主な機能
- サーバー名単位でのアラートキュー管理
- 時間・URL・HTTPメソッドによるアラート検索
- 期限切れアラートの自動クリーンアップ
- アラート保持期間の管理（60秒）
- キューの状態監視・デバッグ機能

## データ構造

### ModSecurityAlertレコード
```java
public static record ModSecurityAlert(
    String ruleId,          // ModSecurityルールID
    String message,         // アラートメッセージ
    String dataValue,       // データ値
    String severity,        // 重要度
    String serverName,      // サーバー名
    String rawLog,          // 生ログ
    LocalDateTime detectedAt, // 検出時刻
    String extractedUrl     // 抽出されたURL情報
)
```

### 内部データ構造
- `Map<String, Queue<ModSecurityAlert>> alertQueues`: サーバー名単位でアラートキューを管理
- `ALERT_RETENTION_SECONDS = 60`: アラート保持期間（60秒）

## 主なメソッド

### クリーンアップタスク管理（v3.0.0新規追加）
- `public void startCleanupTask(ScheduledExecutorService executor)`
  - **目的**: ModSecurityキューのクリーンアップタスクを開始
  - **パラメータ**: `executor` - スケジューラ実行用のExecutorService
  - **動作**: 30秒間隔で`cleanupExpiredAlerts()`を実行
  - **責務**: 期限切れアラートの定期削除

### アラート管理機能
- `public synchronized void addAlert(String serverName, Map<String, String> extractedInfo, String rawLog)`
  - **目的**: ModSecurityアラートをキューに追加
  - **パラメータ**: 
    - `serverName`: サーバー名
    - `extractedInfo`: ModSecHandlerから抽出された情報
    - `rawLog`: 生ログ
  - **処理**: 
    1. サーバー名別キューを取得（存在しない場合は作成）
    2. ModSecurityAlertレコードを作成
    3. キューに追加
    4. デバッグログ出力

- `public synchronized List<ModSecurityAlert> findMatchingAlerts(String serverName, String method, String fullUrl, LocalDateTime accessTime)`
  - **目的**: 指定されたHTTPリクエストに一致するModSecurityアラートを検索・取得
  - **パラメータ**: 
    - `serverName`: 対象サーバー名
    - `method`: HTTPメソッド
    - `fullUrl`: 完全URL
    - `accessTime`: アクセス時刻
  - **戻り値**: 一致したアラートのリスト
  - **処理フロー**:
    1. サーバー別キューを取得
    2. 各アラートの時間差チェック（60秒以内）
    3. URL一致判定
    4. 一致したアラートを結果リストに追加
    5. 使用済みアラートをキューから削除

### クリーンアップ機能
- `public synchronized void cleanupExpiredAlerts()`
  - **目的**: 期限切れアラートをクリーンアップ
  - **処理**: 
    1. 現在時刻から60秒前の基準時刻を計算
    2. 全キューをチェック
    3. 基準時刻より古いアラートを削除
    4. 削除件数をログ出力

### URL抽出・正規化機能
- `private String extractUrlFromRawLog(String rawLog)`
  - **目的**: RawLogからURL情報を抽出（改善版）
  - **抽出パターン**:
    - `[uri "/path"]`
    - `[file "/path"]`
    - `[request_uri "/path"]`
    - `[matched_var_name "ARGS:param"]`
    - `[data "GET /path HTTP/1.1"]`
    - `GET /path HTTP`パターン
    - `to /path at`パターン
    - `request: "GET /path HTTP/1.1"`
  - **フォールバック**: ModSecHandlerでURL抽出できなかった場合のみ使用

- `private String normalizeExtractedUrl(String url)`
  - **目的**: 抽出されたURLを正規化
  - **処理**: 
    - URLデコード
    - スラッシュ補完
    - 無効な文字列の除外

### URL一致判定機能
- `private boolean isUrlMatching(String alertUrl, String requestUrl, String method)`
  - **目的**: URL一致判定
  - **判定ロジック**:
    1. 完全一致チェック
    2. パス部分一致（クエリパラメータ除去）
    3. 部分一致（アラートURLがリクエストURLに含まれる）
  - **戻り値**: 一致する場合true

### 状態監視機能
- `public synchronized Map<String, Integer> getQueueStatus()`
  - **目的**: キューの現在の状態を取得（デバッグ用）
  - **戻り値**: サーバー名とアラート数のマップ
  - **用途**: 定期チェック時のキュー状況確認

## 処理フロー詳細

### アラート追加フロー
```
1. エージェントからModSecurityログ受信
2. ModSecHandler.extractModSecInfo()で情報抽出
3. ModSecurityQueue.addAlert()でキューに追加
4. サーバー名別キューに保存（時刻・URL情報付き）
```

### アラート検索フロー
```
1. アクセスログ保存時またはo定期チェック時
2. findMatchingAlerts()を呼び出し
3. 時間差チェック（±60秒以内）
4. URL一致判定（複数パターン）
5. 一致したアラートを返却・キューから削除
```

### クリーンアップフロー
```
1. 30秒間隔でcleanupExpiredAlerts()実行
2. 現在時刻-60秒の基準時刻を計算
3. 全キューの期限切れアラートを削除
4. 削除件数をログ出力
```

## 時間差対応メカニズム
- **保持期間**: 60秒（ALERT_RETENTION_SECONDS）
- **時間差許容**: ±60秒以内のアラートとアクセスログを関連付け
- **順序不問**: ModSecurityアラートが先に到着してもアクセスログと適切に関連付け
- **定期チェック**: 5秒間隔でキューに残ったアラートと最近のアクセスログを照合

## URL一致アルゴリズム

### 優先順位
1. **完全一致**: アラートURLとリクエストURLが完全に同じ
2. **パス部分一致**: クエリパラメータを除いたパス部分が一致
3. **部分一致**: リクエストURLにアラートURLが含まれる

### URL正規化処理
- **URLデコード**: %エンコーディングされたURLをデコード
- **スラッシュ補完**: 先頭にスラッシュがない場合は追加
- **無効文字列除外**: ARGS:, REQUEST_等を含む場合は除外

## 同期処理とスレッドセーフ
- 全てのpublicメソッドは`synchronized`で同期化
- ConcurrentLinkedQueueを使用してスレッドセーフなキュー操作
- マルチスレッド環境での安全な動作を保証

## エラーハンドリング
- URL抽出失敗時は空文字列を返却
- 正規表現エラー時はデバッグログ出力
- キュー操作エラー時は詳細ログ出力
- 例外発生時も処理継続

## パフォーマンス最適化
- サーバー名別のキュー分割でスケーラビリティ向上
- 使用済みアラートの即座削除でメモリ効率向上
- 期限切れアラートの定期クリーンアップ
- デバッグログによる処理状況の可視化

## アーキテクチャでの位置づけ
```
NginxLogToMysql
├── ModSecurityQueue (キュー管理) ←ここ
├── ModSecHandler (アラート処理)
└── AgentTcpServer (TCP通信)
```

## 依存関係
- **ModSecHandler**: アラート情報の抽出処理
- **AppLogger**: ログ出力
- **Java 21**: Record、Stream API等の最新機能
- **ConcurrentLinkedQueue**: スレッドセーフなキュー実装

## 設定可能項目
- `ALERT_RETENTION_SECONDS`: アラート保持期間（現在60秒）
- クリーンアップ実行間隔（現在30秒）
- 定期チェック実行間隔（現在5秒、ModSecHandlerで管理）

## 注意事項
- キュー管理タスクは外部（NginxLogToMysql）で初期化・開始
- アラート検索後は一致したアラートをキューから削除
- メモリリークを防ぐため期限切れアラートは確実に削除
- デバッグログは本番環境では適切にレベル調整

## バージョン情報
- **ModSecurityQueue.md version**: v1.0.0
- **最終更新**: 2025-08-13
- **主要変更**: 初版作成・v3.0.0アーキテクチャ対応
- ※このファイルを更新した場合は、必ず上記バージョン情報も更新すること
