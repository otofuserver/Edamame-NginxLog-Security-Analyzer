# WebConfig

対象: `src/main/java/com/edamame/web/config/WebConfig.java`

## 概要
- Web アプリケーション固有の静的設定を集めたクラス。セッション有効期限、スレッドプールサイズ、アプリ名などの定義と、HTML テンプレート取得ユーティリティを提供する。

## 主な機能
- セッション・Remember-me のデフォルト値の定義
- スレッドプールサイズ等の定数定義
- HTML テンプレート（ダッシュボード / エラーページ / デフォルト）の取得（`getTemplate`）
- 静的リソース（/static）をクラスパスから読み込むユーティリティ（`getStaticResource`）
- 設定妥当性チェック（`validateConfiguration`）をコンストラクタで実行する

## 挙動
- コンストラクタで `validateConfiguration()` を呼び、定数が論理的に許容範囲内かを検査する（例: SESSION_TIMEOUT_HOURS は 1〜168 時間等）。
- `getTemplate(String templateName)` はテンプレート名に応じて内部のテンプレート文字列を返す（`dashboard`, `error`, default）。ダッシュボードテンプレートはセキュリティヘッダーやメニュー等のプレースホルダを含む。
- `getStaticResource(String resourceName)` は `/static/` 配下のリソースをクラスパスから読み込み UTF-8 文字列で返す（見つからない場合は null）。

## 細かい指定された仕様
- 設定範囲チェックは起動時の安全性確保のため必須。例外が投げられる場合は起動失敗を許容する設計。
- ダッシュボード HTML のテンプレートは用途に応じてプレースホルダ（{{APP_TITLE}} 等）を含むプレーン文字列で返す。
- 将来的な設計では外部テンプレートエンジン（Thymeleaf など）へ移行可能であるが、現状は組み込みテンプレートを利用する。

## メソッド一覧と機能
- `public WebConfig()` - コンストラクタ（設定検証を行う）
- `public String getAppTitle()` - アプリ名の取得
- `public String getAppDescription()` - アプリ説明の取得
- `public String getTemplate(String templateName)` - 指定テンプレートを取得（`dashboard`, `error`, default）
- `public String getStaticResource(String resourceName)` - クラスパスの static リソースを読み込む

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(web): WebConfig の仕様書を追加

