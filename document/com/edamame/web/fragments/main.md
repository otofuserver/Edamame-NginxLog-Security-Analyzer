# main.html フラグメント仕様

対象: `src/main/resources/fragments/main.html`

概要

- `main.html` はサーバ側でレンダリングされるトップレベルの断片で、サイドメニューとアプリ名・バージョンのみを表示するシンプルな断片です。

主な機能

- サイドメニューの HTML（`{{MENU_HTML}}`）を表示するコンテナを提供する。
- アプリ名（`{{APP_TITLE}}`）とバージョン（`{{APP_VERSION}}`）を表示する。

挙動

- この断片は `MainController.renderFullPage()` によりフルページに埋め込まれるか、`/api/fragment/main` 経由で断片として取得される。
- `{{MENU_HTML}}` はサーバ側で置換され、必ず AuthenticationFilter 経由で生成されたメニュー HTML が埋め込まれる想定。

細かい指定された仕様

- プレースホルダは置換前に HTML エスケープされない（サーバ側で安全性を確保すること）。
- この断片はクライアント側での自動レンダリング更新を行わない（`data-auto-refresh` は 0 として返すこと）。

その他

- フラグメントの CSS クラスは既存のダッシュボードスタイルに準拠している。必要に応じて `document/resources/static` のスタイル仕様を更新する。

変更履歴

- 2026-01-11: 新規作成（main ページの断片化対応）。

