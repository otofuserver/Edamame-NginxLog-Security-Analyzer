# DataService

対象: `src/main/java/com/edamame/web/service/DataService.java`

## 概要
- データベースからダッシュボードや一覧表示用のデータを取得するサービスクラス。
- SQL を直接発行して集計・整形を行い、フロントエンドへ渡すための Map/List を構築する。

## 主な機能
- ダッシュボード向け統計取得 (`getDashboardStats`)：総アクセス、攻撃数、ModSecurity ブロック数、サーバー統計、最近のアラート等をまとめて返却。
- サーバー一覧取得 (`getServerList`)：照合順序に注意したサーバー一覧・最終ログ・状態を取得。
- サーバー単位集計 (`getServerStats`)：サーバごとのアクセス数や攻撃検知数、ModSec ブロック数を集計。
- 最近のアラート取得 (`getRecentAlerts`)：modsec_alerts と access_log を組み合わせてアラート情報を返却。
- サポート系ユーティリティ：日時フォーマット、数値フォーマット、ステータス判定など。

## 挙動
- 各メソッドは内部で SQL を組み立てて PreparedStatement を使い実行する。例外時はログ出力 (`AppLogger.error`) して可能な限り安全なデフォルト値（空リストや0）を返す。
- 返却する Map/List のキー名はフロントエンド（テンプレート）側で期待されるキーに合わせてある（例: `serverName`, `lastLogReceived`, `todayAccessCount`）。

## 細かい指定された仕様
- 日時は `yyyy-MM-dd HH:mm:ss` フォーマットで返す（`formatDateTime` 使用）。
- `getServerList` は `server_name` の照合順序を `utf8mb4_unicode_ci` で指定して整列する。
- SQL 実行時の SQLException はキャッチしてログ出力し、呼び出し元に影響を与えないようにする。

## 存在するメソッドと機能（主なもの）
- `public Map<String, Object> getDashboardStats()` - ダッシュボード用に複数の統計をまとめて返す。
- `public List<Map<String, Object>> getRecentAlerts(int limit)` - 最新アラートを取得。
- `public List<Map<String, Object>> getServerList()` - サーバー一覧取得
- `public List<Map<String, Object>> getServerStats()` - サーバ別統計取得
- `public List<Map<String, Object>> getAttackTypeStats()` - 攻���タイプ別統計
- `public Map<String, Object> getApiStats()` - API 用の簡易統計
- `public boolean isConnectionValid()` - DB 接続有効チェック
- `public boolean disableServerById(int id)` - サーバ無効化
- `public boolean enableServerById(int id)` - サーバ有効化
- `private String formatDateTime(Timestamp)` - 日時フォーマット
- `private String determineServerStatus(boolean, Timestamp)` - サーバ状態判定

## 変更履歴
- 2026-01-09: `scheduleAddServerById` を廃止（フロントの "後で追加" 機能を撤去に合わせて削除）。

## その他
- データベーススキーマ変更時は `document/com/edamame/security/db/DbSchema.md` と `document/com/edamame/web/service/DataService.md` の両方を更新すること。

## コミットメッセージ例
- docs(service): DataService の仕様書を追加・schedule_add 廃止を反映

