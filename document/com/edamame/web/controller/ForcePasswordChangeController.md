# ForcePasswordChangeController

対象: `src/main/java/com/edamame/web/controller/ForcePasswordChangeController.java`

## 概要
- 初回ログイン時にパスワード変更を強制する専用ページを提供するコントローラー。認証済みユーザーのみアクセス可能で、変更完了後に must_change_password を解除する。

## 主な機能
- GET `/password/change`: 初回パスワード変更フォームを表示
- POST `/password/change`: 現在パスワード検証と新パスワード設定、変更必須フラグの解除

## 挙動
- Cookie からセッションを取得し `AuthenticationService.validateSession` で検証。無効なら `/login` にリダイレクト。
- POST 時にフォームをパースし、ポリシー違反・不一致・現在パスワード誤り・同一パスワード再利用を検出してエラー表示。
- `UserService.resetPassword(..., requireChangeNextLogin=false)` でパスワードを更新し、成功後 `/main?view=main` にリダイレクト。

## 細かい指定された仕様
- パスワードポリシー: 8文字以上、英字・数字・記号(!@#$%&*()\-@_)を各1文字以上含み、許可文字のみで構成。
- 旧パスワードと同一値は禁止。
- 現在パスワード照合には `AuthenticationService.verifyPassword` を使用。
- レスポンスは Content-Type `text/html; charset=UTF-8`、Cache-Control `no-cache, no-store, must-revalidate` を付与。

## 主なメソッド
- `public void handle(HttpExchange exchange)`
- `private void handlePost(HttpExchange exchange, String username)`
- `private boolean isValidPassword(String pw)`
- `private String buildHtml(String message)`
- `private void renderPage(HttpExchange exchange, String message)`
- `private Map<String,String> parseForm(String body)`
- `private void redirect(HttpExchange exchange, String location)`
- `private void sendError(HttpExchange exchange, int status, String msg)`

## その他
- HTMLはインラインCSS/JSなしのシンプルなフォーム構成。CSPは AuthenticationFilter が付与する。

## 変更履歴
- 1.0.0 - 2026-02-08: 新規作成（初回パスワード変更フロー追加）

## コミットメッセージ例
- docs(web): add spec for ForcePasswordChangeController

