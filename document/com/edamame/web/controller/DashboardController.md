# DashboardController
- docs(web): DashboardController の仕様書を追加
## コミットメッセージ例

- 1.0.0 - 2025-12-31: ドキュメント作成
## 変更履歴

- `private String generateSecureServerStatsHtml(Object serverStatsData)` - サーバ統計 HTML 生成
- `private String generateSecureAttackTypesHtml(Object attackTypesData)` - 攻撃タイプ HTML 生成
- `private String generateSecureServersHtml(Object serversData)` - サーバ一覧 HTML 生成
- `private String generateSecureAlertsHtml(Object alertsData)` - アラート HTML 生成
- `private String generateSecureDashboardHtml(Map<String,Object> data)` - ダッシュボード HTML 生成
- `private boolean validateRequest(HttpExchange exchange)` - リクエスト検証（XSS/SQL）
- `private void applySecurityHeaders(HttpExchange exchange)` - セキュリティヘッダ適用
- `public void handleDashboard(HttpExchange exchange)` - ダッシュボード処理の本体
- `public void handle(HttpExchange exchange)` - HttpHandler 実装エントリ（GET のみ）
- `public DashboardController(DataService dataService)` - コンストラクタ
## メソッド一覧と機能（主なもの）

- 各 HTML 生成メソッド（`generateSecureAlertsHtml`, `generateSecureServersHtml`, `generateSecureAttackTypesHtml`, `generateSecureServerStatsHtml`）は入力型を検査し、Map/List の場合に安全に整形して出力する。
- CSP の nonce を生成してスクリプトソースに付与する方式を採用している（nonce を script-src に付与）。
- テンプレートは `WebConfig.getTemplate("dashboard")` を用い、プレースホルダ置換で出力する。
## 細かい指定された仕様

- `DataService` の接続が無効な場合は 503 を返す。
- 受け取ったデータはすべて `WebSecurityUtils` でサニタイズ/エスケープして HTML に埋め込む。
- GET 以外は 405 を返す。セッション情報（`username`）が Exchange の属性にない場合は `/login` へリダイレクトする。
## 挙動

- ダッシュボード用の各種 HTML 部分（サーバ一覧、アラート、統計、攻撃タイプ）の生成（サニタイズを徹底）
- リクエスト検証（User-Agent / Referer / Query）
- セキュリティヘッダー適用（CSP 等）
- ダッシュボードページの GET 表示（`handleDashboard`）
## 主な機能

- ダッシュボード表示を担当する HTTP ハンドラ。XSS/SQL インジェクション対策を強化したサーバ側レンダリングを行い、`DataService` から取得した統計情報を安全に HTML として返す。
## 概要

対象: `src/main/java/com/edamame/web/controller/DashboardController.java`


