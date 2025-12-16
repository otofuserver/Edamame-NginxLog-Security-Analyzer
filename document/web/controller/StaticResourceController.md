# StaticResourceController 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/controller/StaticResourceController.java`

責務:
- `/static/*` 等の静的リソース（CSS/JS/favicon）を返却する。
- もし `WebConfig` が該当リソースを提供していればそれを返却し、なければデフォルトのリソースを生成して返す。

実装上の注意点:
- 許可されるリソース名・拡張子はホワイトリストで制御する（XSS やパス遡行対策）。
- デフォルトで生成される `dashboard.js` に `location.reload()` を含む箇所があるため、該当リソースが読み込まれてしまうとページ全体リロードが発生する。不要であればデフォルト生成から `location.reload()` を削除すること。

CSP とキャッシュ:
- 静的リソースは適切な `Cache-Control` を設定しつつ、セキュリティヘッダ（CSP）との整合性を保つこと。

エラー処理:
- 存在しないリソースは 404 を返すか、セキュリティの観点からデフォルト安全コンテンツを返す。
