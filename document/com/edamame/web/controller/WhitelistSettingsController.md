# WhitelistSettingsController

対象: `src/main/java/com/edamame/web/controller/WhitelistSettingsController.java`

## 概要
- ホワイトリスト設定画面/設定APIを提供するHTTPハンドラ。adminのみ閲覧・更新可能。

## 主な機能
- `/api/fragment/whitelist_settings` で設定フラグメントHTMLを返却。
- `/api/whitelist-settings` GET で現在のホワイトリストモードと許可IP一覧を取得。
- `/api/whitelist-settings` PUT でモード切替とIPリストを更新し、監査ロールへメール通知を送信。

## 挙動
- CookieからセッションIDを抽出し、`AuthenticationService.validateSession` で認証。`UserServiceImpl.isAdmin` でadmin判定し、非管理者は403を返す。
- GET/PUT 以外や未知パスは404、未認証は401、バリデーションエラーは400、内部例外は500を返す。
- レスポンスは `Content-Type` とセキュリティヘッダー(`WebSecurityUtils.getSecurityHeaders`)を付与。
- 差分（モード変更/追加IP/削除IP）がある場合のみ `MailActionHandler.sendToRoleIncludingHigherRoles("auditor", ...)` で監査メールを送信。送信失敗はWARNログのみで処理継続。

## 細かい指定された仕様
- PUT リクエストBody: `{ "whitelistMode": boolean, "whitelistIps": ["ip", ...] }`。`whitelistIps` が配列でない場合は文字列を単一要素として扱い、nullは空配列。
- GET/PUT レスポンス: `{ "whitelistMode": boolean, "whitelistIps": ["ip", ...] }`。
- セッション検証に成功したadminのみ各操作を許可。
- 監査メール件名例: `[Edamame] ホワイトリスト変更: ON→OFF`。本文に操作者・実行時刻・追加/削除IP・更新後一覧を含める。送信元は `noreply@example.com` / `Edamame Security Analyzer`。

## メソッド一覧
- `handle(HttpExchange)` ルーティングと認可、CORS/OPTIONS処理。
- `handleFragment(HttpExchange)` フラグメントHTMLを返却。
- `handleGetSettings(HttpExchange)` 現在設定を取得してJSON返却。
- `handleUpdate(HttpExchange, String)` 入力JSONを検証し更新・差分返信・監査メール送信。
- `sendWhitelistAuditMail(WhitelistUpdateResult, String)` 差分がある場合に監査メール通知。
- `readJson(HttpExchange)` リクエストボディをMapとして読み込み。
- `sendJson(HttpExchange, int, Map<String,Object>)` JSONレスポンス送信。
- `sendHtml(HttpExchange, int, String)` HTMLレスポンス送信。
- `sendJsonError(HttpExchange, int, String)` エラーJSON送信。
- `applySecurityHeaders(HttpExchange)` セキュリティヘッダー適用。

## その他
- 監査メールはロール階層を考慮してauditorと上位ロールに配信。
- `MailActionHandler` は `NginxLogToMysql.getSharedMailHandler()` 共有インスタンスを優先利用。

## 変更履歴
- 2026-02-11: 監査メール送信と差分通知対応を追加し、初版ドキュメント作成。

