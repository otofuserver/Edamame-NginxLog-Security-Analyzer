# UrlSuppressionController

対象: `src/main/java/com/edamame/web/controller/UrlSuppressionController.java`

## 概要
- URL抑止管理の HTTP エンドポイントを提供するコントローラ。断片HTML返却と CRUD API（一覧/作成/更新/削除/有効切替）を担当。
- 認証済みセッションを前提とし、operator 以上が閲覧、admin のみ更新を許可する。

## 主な機能
- 断片HTML `url_suppression` の返却（権限メタ付き）。
- 抑止ルール一覧の取得（キーワード・サーバー・ソート・ページング）。
- 抑止ルールの作成・更新・削除。

## 挙動
- 認証: セッションIDを検証し、未認証は 401。
- 権限: operator 以上で一覧/断片取得、admin で作成/更新/削除。
- 一覧取得では `page/size/sort/order/q/server` を受け取り `UrlSuppressionService` で検索し、`total/totalPages/page/size/canEdit` を返却。
- 断片取得は `FragmentService` からHTMLを取得し、`data-can-edit`/`data-current-user` を埋め込む。

## 細かい指定された仕様
- `server_name` フィルタ指定時も `all` ルールを含める（サービス側）。
- すべてのレスポンスに `Cache-Control: no-cache` と基本的なセキュリティヘッダを付与。
- パス: `/api/fragment/url_suppressions`（GET）, `/api/url-suppressions`（GET/POST）, `/api/url-suppressions/{id}`（PUT/DELETE）。

## その他
- JSONシリアライズは `ObjectMapper` (JavaTimeModule) を利用し、日時はタイムスタンプを書き出さない設定。

## 存在するメソッドと機能
- `public void handle(HttpExchange exchange)`: ルーティング・認証/権限チェックのエントリポイント。
- `private void handleFragment(...)`: 断片HTML返却。
- `private void handleList(...)`: 一覧取得（ページング対応）とレスポンス生成。
- `private void handleCreate(...)`: 新規作成。
- `private void handleUpdate(...)`: 既存更新。
- `private void handleDelete(...)`: 削除。
- `private long parseId(String path)`: URL から ID 抽出。
- `private Map<String,Object> readJson(HttpExchange exchange)`: リクエストJSON読取。
- `private List<Map<String,Object>> sanitize(...)`: 文字列サニタイズ。
- `private void sendJson/sendJsonError/sendHtml(...)`: レスポンス送出ユーティリティ。
- `private void applySecurityHeaders(HttpExchange exchange)`: セキュリティヘッダ付与。
- `private int parseIntOr(String val, int fallback)`: page/size 用の安全な整数変換。

## 変更履歴
- 2026-01-20: URL抑止管理 API/断片コントローラの仕様書を新規作成。
