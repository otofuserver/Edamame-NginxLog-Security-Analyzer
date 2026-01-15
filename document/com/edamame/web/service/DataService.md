# DataService

対象: `src/main/java/com/edamame/web/service/DataService.java`

## 概要
- データベースからダッシュボードや一覧表示用のデータを取得するサービスクラス。
- SQL を直接発行して集計・整形を行い、フロントエンドへ渡すための Map/List を構築する。

## 主な機能
- ダッシュボード向け統計取得 (`getDashboardStats`)：総アクセス、攻撃数、ModSecurity ブロック数、サーバー統計、最近のアラート等をまとめて返却。
- サーバー一覧取得 (`getServerList`)：照合順序に注意したサーバー一覧・最終ログ・状態を取得（管理画面向けに全サーバーを返す）。
- サーバー単位集計 (`getServerStats`)：サーバごとのアクセス数や攻撃検知数、ModSec ブロック数を集計（ダッシュボード用は有効サーバのみを対象）。
- 最近のアラート取得 (`getRecentAlerts`)：modsec_alerts と access_log を組み合わせてアラート情報を返却。
- 攻撃タイプ別統計取得 (`getAttackTypeStats`)：today の access_log を基に集計するロジックを備える（ダッシュボードでは除外可能）。
- URL脅威度一覧取得（`getUrlThreats`）: サーバーフィルタ・脅威度フィルタ・キーワード検索に対応。`url_registry` の `latest_*` カラムのみを参照して最新状態を返却（access_log への依存を排除）。

## 挙動
- 各メソッドは内部で SQL を組み立てて PreparedStatement を使い実行する。例外時はログ出力 (`AppLogger.error`) して可能な限り安全なデフォルト値（空リストや0）を返す。
- 返却する Map/List のキー名はフロントエンド（テンプレート）側で期待されるキーに合わせてある（例: `serverName`, `lastLogReceived`, `todayAccessCount`）。

## 細かい指定された仕様
- 日時は `yyyy-MM-dd HH:mm:ss` フォーマットで返す（`formatDateTime` 使用）。
- `getServerList` は管理画面向けに全サーバーを返す（`is_active` に関係なく一覧化）。
- `getServerStats` はダッシュボード用に `s.is_active = TRUE` のサーバーのみを対象に統計を集計する。
- 全ての集計でサーバ名の照合順序は `COLLATE utf8mb4_unicode_ci` を利用している。
- `getAttackTypeStats()` は access_log をベースに今日の攻撃を集計する SQL (only_full_group_by に対応したサブクエリ方式) を持つが、ダッシュボードからは除外される設定。
- SQL の集計は `COUNT(DISTINCT al.id)` を利用し重複カウントを避ける。

## 存在するメソッドと機能（主なもの）
- `public Map<String, Object> getDashboardStats()` - 複数の統計をまとめて返す。
- `public List<Map<String, Object>> getRecentAlerts(int limit)` - 最新アラートを取得。
- `public List<Map<String, Object>> getServerList()` - サーバー一覧取得（管理画面向け: 全サーバー）。
- `public List<Map<String, Object>> getServerStats()` - サーバ別統計取得（ダッシュボード用: 有効サーバのみ）。
- `public List<Map<String, Object>> getAttackTypeStats()` - 攻撃タイプ別統計（access_log ベースの集計。ダッシュボードでは表示しない）。
- `public List<Map<String, Object>> getUrlThreats(String serverName, String threatFilter, String query)` - URL脅威度一覧取得。`url_registry.latest_*` を利用し、フィルタ・検索・優先度ソートに対応。
- `public Map<String, Object> getApiStats()` - API 用の簡易統計。
- `public boolean isConnectionValid()` - DB 接続有効チェック。
- `public boolean disableServerById(int id)` / `public boolean enableServerById(int id)` - サーバーの有効/無効切替。

## 変更履歴
- 2025-12-31: 初期ドキュメント作成。
- 2026-01-06: `getServerStats` と `getServerList` の照合順序対応と today 集計追加。
- 2026-01-10: `getServerStats` の SQL 構文エラーを修正（LEFT JOIN と WHERE の順序を正す）。
- 2026-01-11: ダッシュボード向けに `getServerStats` を有効サーバーのみに限定、`getServerList` は管理画面向けに全サーバーを返すように調整。`getAttackTypeStats` を only_full_group_by 対応のサブクエリ方式で実装し、一時的にダッシュボード表示を除外。
- 2026-01-15: URL脅威度一覧取得メソッド `getUrlThreats` を追加。サーバーフィルタ・脅威度フィルタ・キーワード検索に対応。`url_registry` の `latest_*` カラムのみを参照して最新状態を返却（access_log への依存を排除）。

## コミットメッセージ例
- docs(service): DataService の仕様を更新（URL脅威度一覧を url_registry 最新メタで返却）
