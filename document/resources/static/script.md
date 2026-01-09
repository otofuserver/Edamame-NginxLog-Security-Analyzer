# script.js

対象: `src/main/resources/static/script.js`

## 概要
- フロントエンドの軽量ブートストラップ兼ユーティリティスクリプト。ページロード時にコアUIモジュールを順に読み込み、分割された機能モジュール（`clock.js`, `fragment_refresh.js`, `navigation.js`）を初期化する役割を持つ。
- 逐次読み込みローダー（`loadScript` / `loadScriptsSequential`）をグローバルに公開し、他モジュールからのスクリプト読み込みを一本化している。

## 主な機能
- スクリプトの順次読み込み（グローバル: `loadScript`, `loadScriptsSequential`）
- サイドバーのリンククリックをインターセプトしてクライアントナビゲーション（`navigateTo`）へ委譲
- popstate を用いたブラウザ戻る/進むのサポート
- コアモジュールの順次読み込み（`sidebar_mini_menu.js`, `profile_modal.js`, `password_modal.js`, `logout.js`）
- 分割モジュール（`clock.js`, `fragment_refresh.js`, `navigation.js`）の読み込みと初期化開始
- デバッグユーティリティ `window.dbg` の提供

## 挙動
- `DOMContentLoaded` 時にコアモジュールを逐次ロードし、初期化フックを呼ぶ。
- 続けて `clock.js`, `fragment_refresh.js`, `navigation.js` を読み込む。これらは分割された機能（時計、断片自動更新、断片ナビゲーション）を提供する。
- `loadScriptsSequential` は各スクリプトを同期的に読み込み順序を保証するために使用される。
- ページ内 `#main-content` の `data-no-client-nav` 属性が `true` の場合はサーバ側でレンダリングされたコンテンツを尊重し、必要なクライアント初期化のみ行う。

## 細かい指定された仕様
- `window.loadScript` / `window.loadScriptsSequential` をグローバルに公開し、他モジュールはこれを利用すること（一本化）。
- 分割ファイルが `script.js` より先に読み込まれるのを許容しない（`fragment_refresh.js` はグローバル loader を必須とする仕様に変更済み）。
- `startClock`, `setupFragmentAutoRefresh`, `navigateTo` 等の初期化呼び出しは、対応モジュールがロードされているかを確認してから行う。
- CSP や非同期ロードの影響により `async=false`（同期挿入）でスクリプトを追加している点に注意。

## その他
- `script.js` はブートストラップ責務のみに限定し、UIロジックや断片ロジックは分割ファイルへ移譲することで可読性を向上させた。
- 今後、追加機能を分割する場合は `script.js` の `loadScriptsSequential` 呼び出し箇所を更新すること。

## 主な関数一覧
- `loadScript(url)` - 単一スクリプトを動的挿入して読み込む
- `loadScriptsSequential(urls)` - 配列のスクリプトを逐次的に読み込む（グローバル公開）
- `dbg(...args)` - デバッグ出力ユーティリティ（`window.dbg`）
- DOMContentLoaded ハンドラ - コアモジュール/分割モジュールの逐次読み込みと初期化を実行

## 変更履歴
- 2026-01-09: `script.js` を軽量ブートストラップ化し、`clock.js`, `fragment_refresh.js`, `navigation.js` へ機能分割。`loadScriptsSequential` をグローバル公開してローダを一本化。

## コミットメッセージ例
- docs(front): script.js の仕様を分割後の構成に合わせて更新
