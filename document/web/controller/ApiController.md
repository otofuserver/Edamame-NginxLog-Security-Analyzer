# ApiController 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/controller/ApiController.java`

責務:
- フロントエンドからの AJAX リクエスト（JSON ベース）を処理するエンドポイント群を提供する。
- データ取得・操作（例: サーバリスト、アラート、設定取得）を RESTful-ish に提供する。

エンドポイント（例）:
- GET `/api/servers` -> サーバ一覧 JSON
- GET `/api/alerts` -> 最近のアラート JSON

認証/認可:
- API は同一オリジンのクレデンシャル付きリクエスト（`credentials: 'same-origin'`）で利用される。
- 未認証の場合は 401 を返し、フロントエンドでログインページにリダイレクトする。

エラー処理:
- 404: リソースが存在しない場合
- 500: サーバ内部エラー

備考:
- API 仕様を変更する場合は `document/web/controller/ApiController.md` を更新し、フロントエンドの `script.js` の対応を合わせること。
