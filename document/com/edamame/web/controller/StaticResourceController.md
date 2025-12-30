# StaticResourceController

対象: `src/main/java/com/edamame/web/controller/StaticResourceController.java`

## 概要
- CSS / JavaScript / 画像等の静的リソースをクラスパス（/static）から提供するコントローラ。リクエスト入力に対するサニタイズと MIME タイプ判定、キャッシュ制御を行い安全に配信する。

## 主な機能
- 静的ファイルの GET 提供（`handleStaticResource`）
- `favicon.ico` の専用処理（`handleFavicon`）
- リソース名の抽出とサニタイズ（`extractSecureResourceName`）
- 許可された拡張子チェックとコンテンツタイプ判定（`isAllowedResource`, `getContentType`）
- セキュリティヘッダ適用（nosniff, X-Frame-Options 等）

## 挙動
- GET 以外は 405 を返す。リクエストの path/query/User-Agent を検査し、不正であれば 400 を返す。
- 指定されたリソースがクラスパスに存在すればそのバイト列を返却し、存在しなければデフォルトコンテンツ（dashboard.css など）を生成して返す。
- 静的リソースは Cache-Control を設定（例: public, max-age=3600）してクライアント側キャッシュを許容する。

## 細かい指定された仕様
- 許可拡張子は .css, .js, .png, .jpg, .jpeg, .gif, .svg, .ico。ファイル名長は 100 文字以内に制限する。
- リソース名抽出時にパス・トラバーサル文字列（../, ..\）やパーセントエンコーディングの迂回をチェックする。
- `handleFavicon` は `/static/favicon.ico` をクラスパスから読み込み、Content-Type を image/x-icon に設定して返す。

## メソッド一覧と機能（主なもの）
- `public StaticResourceController()` - コンストラクタ
- `public void handle(HttpExchange exchange)` - エントリポイント
- `public void handleStaticResource(HttpExchange exchange)` - 静的リソース配信処理
- `public void handleFavicon(HttpExchange exchange)` - favicon 配信専用処理
- `private boolean isValidStaticResourceRequest(HttpExchange exchange)` - リクエスト検証
- `private String extractSecureResourceName(String path)` - リソース名抽出とサニタイズ
- `private String getSecureResourceContent(String resourceName)` - リソース読み込み / デフォルト生成
- `private boolean isAllowedResource(String resourceName)` - 許可リソース判定
- `private String getContentType(String resourceName)` - MIME タイプ判定

## 変更履歴
- 1.0.0 - 2025-12-31: ドキュメント作成

## コミットメッセージ例
- docs(web): StaticResourceController の仕様書を追加

