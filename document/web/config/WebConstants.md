# WebConstants 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象ファイル: `src/main/java/com/edamame/web/config/WebConstants.java`

内容:
- Web アプリケーションで再利用される定数（セッションキー、コンテンツタイプ、パス等）を定義するファイル。

例（想定）:
- `SESSION_COOKIE_NAME` / `CSRF_TOKEN_HEADER` / `DEFAULT_CONTENT_TYPE` など。

変更ルール:
- 定数を追加・変更する場合は、影響範囲（全コントローラ・フィルタ）をレビューの上、仕様書と CHANGELOG.md を更新すること。
