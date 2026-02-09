# sidebar_settings_menu.js

対象: `src/main/resources/static/sidebar_settings_menu.js`

## 概要
- サイドバー「設定」ボタンでミニメニューを開き、権限に応じて URL指定非監視設定ビューへの導線を提供する初期化スクリプト。

## 主な機能
- サイドバーの設定ボタンとミニメニュー要素を取得し、クリックで `mini_menu.js` を用いたコンテキストメニューを表示する。
- ミニメニュー項目「URL指定非監視設定」を生成し、選択時に `navigateTo('url_suppression')` で SPA 遷移（フォールバックで通常遷移）。
- 管理者またはオペレーターのみ操作可能にするため、`current-user-admin` / `current-user-operator` の data 属性から権限を確認し、`canOperate` を渡す。
- `aria-expanded` の更新とトグル動作、外側クリック時の閉じ処理を行う。

## 挙動
- DOM 初期化後に `SidebarSettingsMenu.init()` を呼び出すと、設定ボタンにクリックハンドラを一度だけバインドする。
- ミニメニュー表示中に再クリックすると閉じる（トグル）。
- ミニメニュー外側をクリックすると閉じ、`aria-expanded` を `false` に戻す。
- 権限がない場合は `requirePermission` により項目が無効化され、実行できない。

## 細かい指定された仕様
- `mini_menu.js` の `MiniMenu.create` を利用し、`canOperate` を `admin || operator` 判定で付与する。
- 項目定義: label="URL指定非監視設定", requirePermission=true, onClick -> navigateTo("url_suppression").
- 位置計算はボタンの `getBoundingClientRect` を使用し、x=中央、y=ボタン下端で表示。
- 二重バインド防止のため `__edamame_settings_bound__` フラグでガードする。

## その他
- ナビゲーションフォールバックとして `window.navigateTo` 不在時は `/main?view=url_suppression` へ遷移する。
- メニュー外観・表示/非表示は `mini_menu.js` / `mini_menu.css` に依存。

## 変更履歴
- 2026-02-10: 初版作成。設定ミニメニュー（URL指定非監視設定導線）と権限制御を仕様化。

