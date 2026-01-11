概要

MainController はアプリケーションのメインページ（/main）のサーバ側レンダリングを担当します。サイドメニュー・アプリ名・バージョン表示など、フルページ向けのテンプレート置換と断片生成の仲介を行います。

主な機能

- /main へのリクエストを受け、適切な断片（main / dashboard 等）を埋め込んだフルHTMLを生成して返却する。
- 認証フィルタ(AuthenticationFilter) が設定したリクエスト属性（username, isAdmin, scriptNonce）を利用して表示を制御する。
- クライアント側の強制ナビゲーションでサーバレンダリングが上書きされないよう `data-no-client-nav` 属性を `<main>` 要素に付与する。
- generateMenuHtml, generateUserInitial 等のユーティリティを保持（メニューは AuthenticationFilter 経由で生成されることを厳格化）。

挙動

- 入力: HttpExchange（認証済のリクエスト）
- 出力: サーバ側で置換済みの HTML（Content-Type: text/html; charset=UTF-8）
- 例外: DB/テンプレート読み込みエラー時は 500 を返却し、error テンプレートを表示

細かい指定された仕様

- テンプレート置換項目（必須）
  - {{APP_TITLE}}: webConfig.getAppTitle() の HTML エスケープ版
  - {{APP_VERSION}}: webConfig.getAppVersion()（または指定バージョン）
  - {{MENU_HTML}}: AuthenticationFilter 経由で生成されたメニュー HTML（フォールバック無効）
  - {{CURRENT_USER}} / {{CURRENT_USER_INITIAL}}: username とそのイニシャル
  - {{CURRENT_VIEW}}: サーバ側でレンダリングした view を固定（例: "dashboard"）
  - {{AUTO_REFRESH_SCRIPT}}: 空文字列（フルページの自動リロードは無効化）
- `data-no-client-nav="true"` 属性を `<main id="main-content" data-view="...">` に追加する
- generateMenuHtml(String username, boolean isAdmin) は AuthenticationFilter 経由でのみ呼ばれることを前提にする（MainController 側でのフォールバックは削除）

存在するメソッドと機能

- public static String renderFullPage(String dashboardWrapped, String username, boolean isAdmin, String currentView)
  - フルページテンプレートを読み込み、上記のプレースホルダを置換して文字列で返す。
  - `data-no-client-nav` 属性の付与を行う。
- public static String generateMenuHtml(String username, boolean isAdmin)
  - メニュー HTML を生成（AuthenticationFilter が生成したものを使用する設計に変更中）
- public static String generateUserInitial(String username)
  - ユーザー名からイニシャル（表示用）を生成するユーティリティ

その他

- CSP nonce や scriptNonce は AuthenticationFilter が生成・リクエスト属性に格納する。MainController はそれを利用するのみ。

変更歴

- 2026-01-11: 新規作成。`/main` のサーバ側レンダリングと `data-no-client-nav` 付与の仕様を追加。

