# WebConfig 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象ファイル: `src/main/java/com/edamame/web/config/WebConfig.java`

概要:
- HTML テンプレート、静的リソース（CSS/JS）を Java の文字列として提供するユーティリティ。
- メニュー HTML を一元管理し、全ページで共通のサイドメニューを生成する。
- CSP を含むセキュリティヘッダーや、自動更新スクリプト（注: グローバル自動更新はコントローラ側で無効化済み）を生成する。

主要メソッド
- `getTemplate(String templateName)` : `dashboard`, `error`, default のテンプレートを返す。
- `getStaticResource(String resourceName)` : `style.css`, `script.js` などの文字列を返す。
- `getMenuHtml()` : サイドメニュー HTML を返す（メニュー���目の編集はここで一元管理）。
- `getJsResource()` : `/static/script.js` として配信される JavaScript を返す（AJAX ナビゲーション、断片自動更新ロジックを含む）。

フラグメント自動更新について
- `getJsResource()` 内に断片単位の自動更新を管理するロジックがある:
  - 各断片はルート要素に `class="fragment-root"` と `data-auto-refresh="<秒>"` を持つ。
  - JS 側は断片切替時に既存の自動更新タイマーを全て停止し、該当断片の自動更新を開始する（`stopAllFragmentAutoRefresh()` / `startFragmentAutoRefresh()`）。
  - テンプレート（test）は `data-auto-refresh="0"` を返し、自動更新を行わない。

CSP と nonce
- `getSecurityHeadersHtml(String scriptNonce)` で CSP の meta を生成し、`script-src` に nonce を付与する仕様。
- 非推奨だが一時的に `style-src 'unsafe-inline'` を許可している箇所があるため、将来的に外部 CSS 化を推奨。

運用ルール
- メニュー項目を変更する際は必ず `getMenuHtml()` を編集し、関連する `fragments/*.html` と `FragmentService` の対応を確認すること。
- テンプレートや静的リソースの変更後は `./gradlew clean; ./gradlew build` を実行してデプロイアーティファクトを更新する。
