# LogoutController

対象: `src/main/java/com/edamame/web/controller/LogoutController.java`

## 概要
- ログアウト処理を担う HTTP ハンドラ。セッションの無効化、Cookie のクリア、およびログアウト後のリダイレクトを行う。

## 主な機能
- セッション取得→無効化（`AuthenticationService.logout`）
- セッションクッキーのクリア（`clearSessionCookies`）
- ログアウト後のリダイレクト（`sendLogoutRedirect`）

## 挙動
- リクエストの Cookie ヘッダからセッション ID を取得し、認証サービスでセッション情報を検証後 `logout` を呼び出す。
- Cookie は `Set-Cookie` ヘッダで Max-Age=0 のクリア値を設定してブラウザ側から削除させる。
- 最終的に `WebConstants.LOGOUT_SUCCESS_REDIRECT` へ 302 リダイレクトする。

## 細かい指定された仕様
- セッションが既に存在しない場合でも Cookie はクリアされ、リダイレクトは行われる（冪等性を重視）。
- 例外発生時は 500 を返す実装（`sendInternalServerError`）。

## メソッド一覧と機能（主なもの）
- `public void handle(HttpExchange exchange)` - エントリポイント
- `private void handleLogout(HttpExchange exchange)` - ログアウト処理本体
- `private String getSessionIdFromCookie(HttpExchange exchange)` - Cookie から sessionId を抽出
- `private void clearSessionCookies(HttpExchange exchange)` - Cookie を削除するヘッダーを追加
- `private void sendLogoutRedirect(HttpExchange exchange)` - ログアウト後のリダイレクトを実行

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成

## コミットメッセージ例
- docs(web): LogoutController の仕様書を追加

