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
- `getMenuHtml()` : サイドメニュー HTML を返す（メニュー項目の編集はここで一元管理）。
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

## 2025-12-29 更新: WebConfig テンプレートの仕様変更

- 変更概要:
  - ダッシュボードの `main` コンテナに `data-view` 属性を埋めるようテンプレートを変更しました（例: `<main id="main-content" data-view="users">`）。
  - サイドバー下部の要素を整理し、時計（`.current-time`）とログアウトボタンを別行に表示するようにHTML構造を変更しました。

- 理由:
  - クライアント側の SPA ライクな断片取得とサーバサイドレンダリングの初期化責務を一貫化するため。
  - `data-view` を用いることで、サーバレンダリングされたページでもクライアントが正しい view を判断して初期化処理を実行できます。

- 影響範囲:
  - クライアント側の `script.js` が `data-view` を優先して初期化を行うようになりました。
  - サーバから返される断片（fragments）やテンプレートを生成する箇所は、必要に応じて `CURRENT_VIEW` を埋める必要があります（既に `DashboardController` 側で設定されています）。

- テスト手順:
  1. サーバを再起動してテンプレートを反映する。  
  2. ブラウザで `/main?view=users` にアクセス（またはログイン後にダッシュボードへ遷移）し、DevTools の Elements で `#main-content` に `data-view` 属性が存在することを確認する。  
  3. クライアント側のユーザー管理 UI（検索・モーダル）が通常通り動作することを確認する。
