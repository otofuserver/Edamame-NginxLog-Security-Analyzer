# sidebar_settings_menu.js

対象: `src/main/resources/static/sidebar_settings_menu.js`

## 概要
- サイドバー「設定」ボタンからミニメニューを開き、設定系ビュー（URL指定非監視・ホワイトリスト設定）への遷移を提供するフロントエンドスクリプト。ユーザーの権限に応じて操作可否を制御し、MiniMenu コンポーネントを用いてポップアップ表示する。

## 主な機能
- 設定ボタンのクリックでミニメニューを表示/非表示に切り替え。
- MiniMenu へ URL指定非監視設定とホワイトリスト設定のメニュー項目を登録。
- 管理者/オペレーター権限を判定し、操作可能フラグを MiniMenu に渡す。
- 外側クリックで aria-expanded をリセットし、メニュー開閉状態を整合させる。

## 挙動
- `window.SidebarSettingsMenu.init()` を呼ぶと、`#sidebar-settings-button` と `#sidebar-settings-mini-menu` を取得し、既にバインド済みなら何もしない。
- MiniMenu を生成できない場合は即座に return し、例外はコンソール警告に留める。
- クリック時にボタン位置から表示座標を計算し、`navigateTo` があれば SPA 遷移、なければ `/main?view=...` へフォールバック遷移する。
- outside click を `document` にバインドし、閉じた際に aria-expanded を false に戻す。

## 細かい指定された仕様
- メニュー項目: `URL指定非監視設定`（requirePermission=true）、`ホワイトリスト設定`（requirePermission=true, admin以外はdisabled）。
- 権限判定要素: `#current-user-operator` の `data-is-operator`, `#current-user-admin` の `data-is-admin`。
- 依存: `window.MiniMenu.create` が利用可能であること。存在しない場合は無動作。
- グローバル公開名: `window.SidebarSettingsMenu`。多重バインド防止に `__edamame_settings_bound__` フラグを使用。

## その他
- 例外は捕捉して `console.warn` に記録し、UI崩壊を避ける設計。
- 設定メニューに今後項目を増やす場合は `items` 配列へ追加し、必要に応じて権限フラグを渡す。

## 変更履歴
- 2026-03-16: ドキュメント新規作成（設定ミニメニュー追加に伴い）。
- 2026-03-16: ブロックIP管理メニューを通常サイドメニューへ移動し、本ミニメニューから削除。

