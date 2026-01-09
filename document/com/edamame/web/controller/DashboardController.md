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
- `generateSecureDashboardHtml(Map<String,Object> data)` はテンプレート `dashboard` を取得して各部分を埋め込み、CSP用nonceを生成して `{{SECURITY_HEADERS}}` に埋め込む。
- CSP の nonce を生成してスクリプトソースに付与する方式を採用している（nonce を script-src に付与）。
- `validateRequest` は User-Agent / Referer / query の XSS/SQL 検査を行い、不正なら false を返す。

## 概要

対象: `src/main/java/com/edamame/web/controller/DashboardController.java`

- ダッシュボード画面のサーバ側レンダリングを担当する HTTP ハンドラ。XSS/SQL インジェクション対策を適用してテンプレートへ安全にデータを埋め込む。

## 主な機能

- ダッシュボード表示（サーバ統計・最近のアラート・サーバ一覧・攻撃タイプ）
- セキュリティヘッダー（CSP等）の付与
- リクエストバリデーション（XSS/SQL 検出）
- サイドバーメニューの動的生成（`generateMenuHtml`）

## 挙動

- GET リクエストのみ許可。未認証時は `/login` へリダイレクト。
- データソースは `DataService.getDashboardStats()` を呼び出し、返却された Map をサニタイズしてテンプレートへ埋め込む。
- テンプレートのプレースホルダに安全に値を埋め込み（`WebSecurityUtils.escapeHtml` 等を使用）て応答する。

## 細かい指定された仕様

- サイドバーメニュー生成で `users` は管理者のみに表示されるが、`servers` リンクは管理者以外でも表示する（2026-01-09 の更新）。
- 現在のユーザーの管理者フラグは隠し DOM 要素として出力される：`<div id="current-user-admin" data-is-admin="true|false" style="display:none;"></div>`。フロントはこれを参照して UI の表示/無効化制御を行う。
- すべての埋め込み値は `WebSecurityUtils.sanitizeInput`／`escapeHtml` を用いてサニタイズされる。
- レスポンスにセキュリティヘッダを設定（CSPにnonceを付与、X-Frame-Options 等）。
- DB接続が無効な場合は 503 を返す。

## その他

- 初期化時に `UserServiceImpl` を内部的に new して管理者チェックを行う箇所があるため、`UserService` の実装差し替えを行う場合は `generateMenuHtml` と API 側の管理者チェック実装を見直すこと。

## 変更履歴（追加）
- 2026-01-09: サイドバーの `servers` リンクを常時表示に変更、`current-user-admin` 隠し要素を追加（フロントの無効化UI制御用）
