# AuthenticationFilter

対象: `src/main/java/com/edamame/web/security/AuthenticationFilter.java`

## 概要
- HTTP ハンドラとして機能する認証フィルタ。`HttpExchange` を受け取り、Cookie からセッション ID を抽出して `AuthenticationService` で検証する。検証成功なら保護されたハンドラに処理を委譲し、失敗時は `/login` へリダイレクトする。

## 主な機能
- Cookie からのセッション ID 取得（`getSessionIdFromCookie`）
- セッション検証と属性のセット（username を `exchange` に設定）
- 認証済みなら `protectedHandler.handle(exchange)` を呼ぶ
- 未認証・無効なセッションは 302 リダイレクトで `/login` へ誘導
- 内部エラー時は 500 応答を返す

## 挙動
- `handle` 内で `authService.validateSession` を呼び、`SessionInfo` が有効であれば `exchange` に username を属性として設定する。
- 例外発生時は `sendInternalServerError` を呼び 500 応答を返す。
- `getSessionIdFromCookie` は `WebConstants.extractSessionId` を利用して Cookie ヘッダを解析する。

## 細かい指定された仕様
- リダイレクトは Location ヘッダに `/login` を設定し、ステータスコード 302 を返す。
- エラー時の応答本文は "Internal Server Error" を UTF-8 で返す（簡易実装）。
- 本クラスは `com.sun.net.httpserver.HttpHandler` を実装しているため、組み込み HTTP サーバで直接利用できる。

## メソッド一覧と機能（主なもの）
- `public AuthenticationFilter(AuthenticationService authService, HttpHandler protectedHandler)` - コンストラクタ
- `public void handle(HttpExchange exchange) throws IOException` - メイン処理
- `private String getSessionIdFromCookie(HttpExchange exchange)` - Cookie からセッション ID を抽出
- `private void sendInternalServerError(HttpExchange exchange) throws IOException` - 500 応答を返す

## その他
- 実運用ではリダイレクト先やログ出力フォーマット、エラーページのカスタマイズを行うこと。

## 変更履歴
- 1.0.0 - 2025-12-30: 新規作成

## コミットメッセージ例
- docs(web): AuthenticationFilter の仕様書を追加

