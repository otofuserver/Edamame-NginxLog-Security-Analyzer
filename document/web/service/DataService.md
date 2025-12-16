# DataService 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/service/DataService.java`

責務:
- DB からダッシュボードに必要なデータ（サーバ統計、アラート、サーバ一覧、攻撃タイプ統計など）を取得する。
- DB 接続の妥当性検証（`isConnectionValid()`）やトランザクション管理はこの層が担う。

主要メソッド:
- `getDashboardStats()` -> Map<String,Object> の形式でダッシュボード表示用データを返す。
- `isConnectionValid()` -> DB 接続チェック

エラー処理:
- DB エラー時は呼び出し側（コントローラ）に例外を投げ、コントローラは 503 を返す。

運用ルール:
- DB 接続時は最大 5 回までリトライする実装方針（プロジェクト全体仕様）。
- スキーマ変更は `DbSchema` で管理し、変更時は `document/db/DbSchema.md` を更新すること。
