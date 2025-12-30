# LoginController

対象: `src/main/java/com/edamame/web/controller/LoginController.java`

## 概要
- 組み込み HTTP サーバ (`com.sun.net.httpserver.HttpHandler`) 向けのログイン処理コントローラ。
- ログインページの表示（GET）とログイン送信（POST）を処理し、`AuthenticationService` を使って認証しセッション Cookie を発行する。

## 主な機能
- ログインページ表示（`handleLoginPage`）
- ログイン処理（`handleLoginSubmit`）
- XSS 検出と入力サニタイズ
- セッション Cookie の生成・設定

## 挙動
- GET リクエストでは既存セッションをチェックし有効ならダッシュボードへリダイレクトする。テンプレートが存在しない場合はフォールバック HTML を返す。
- POST リクエストではフォームデータをパースし、`AuthenticationService.authenticate` を呼び出して成功なら `Set-Cookie` を返却して 302 リダイレクト、失敗ならログイン画面を再表示する。
- 入力に XSS パターンが検出された場合は 400 を返す。

## 細かい指定された仕様
- フォームは application/x-www-form-urlencoded 想定で `parseFormData` が実装されている。
- セッション Cookie の生成は `WebConstants.createSessionCookieValue` を利用する。
- レスポンスは UTF-8 を使用し、Cache-Control ヘッダでキャッシュ無効化を設定する。

## メソッド一覧と機能（主なもの）
- `public void handle(HttpExchange exchange)` - メインエントリ（GET/POST を振り分け）
- `private void handleLoginPage(HttpExchange exchange)` - ログインページ表示処理
- `private void handleLoginSubmit(HttpExchange exchange)` - ログイン送信処理
- `private String generateLoginHtml(String message)` - ログイン HTML を生成するユーティリティ
- `private Map<String,String> parseFormData(String formData)` - フォームデータパース
- `private void setSessionCookie(HttpExchange exchange, String sessionId, boolean rememberMe)` - セッション Cookie 設定
- `private void sendHtmlResponse(HttpExchange exchange, String html)` - HTML 応答
- `private void sendLoginSuccessResponse(HttpExchange exchange)` - ログイン成功時の 302 応答

## セキュリティ/運用
- フォーム入力はサーバ側で再検証し、パスワードは `AuthenticationService` でハッシュ照合すること。
- 初期管理者パスワード（README 等に記載されている場合）は運用初回後に必ず変更する運用を徹底する。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成

## コミットメッセージ例
- docs(web): LoginController の仕様書を追加

