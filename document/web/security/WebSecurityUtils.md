# WebSecurityUtils 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/security/WebSecurityUtils.java`

責務:
- XSS 検出・サニタイズ、URL 表示用のサニタイズ、基本的なセキュリティユーティリティを提供する。
- `getSecurityHeaders()` 等のメソッドで HTTP レスポンスヘッダ（CSP など）を取得するユーティリティも提供する。

主要メソッド例:
- `escapeHtml(String)` : HTML エスケープ
- `sanitizeInput(String)` : 入力サニタイズ（ログや表示用）
- `sanitizeUrlForDisplay(String)` : URL 表示用に安全化
- `detectXSS(String)` : 危険な文字列の検出
- `getSecurityHeaders()` : デフォルトのセキュリティヘッダ（HSTS, X-Frame-Options, CSP など）を返す

運用:
- サニタイズルールを変更する際は、既存の HTML テンプレートと断片の互換性を確認すること。
