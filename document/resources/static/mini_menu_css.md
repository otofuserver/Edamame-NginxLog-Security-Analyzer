# mini_menu.css

対象: `src/main/resources/static/mini_menu.css`

## 概要
- ミニメニューの共通スタイルを集約したCSS。サーバー管理、URL脅威度、URL抑止などで再利用する見た目とレイアウトを提供する。

## 主な機能
- ミニメニューのポジション・可視状態・ポインターイベント制御（`.mini-menu` / `.show`）。
- カード枠・影・余白・角丸などの共通��ザイン（`.mini-menu-card`）。
- メニュー項目のフォント、余白、ホバー、disabled/hidden 状態のスタイル（`.mini-menu-item`）。
- サーバー管理用の z-index 調整（`.server-mini`）。

## 挙動
- デフォルトは非表示かつ `visibility:hidden`、`pointer-events:none`。`.show` 付与で表示・操作可能に切り替わる。
- ビューポート内で使いやすい最小/最大幅（140px〜270px）を設定し、クリック位置に合わせたJS側の座標指定を受け取る前提。
- サイドバーのユーザーフッターに相対配置するため `.sidebar .sidebar-user-footer` を `position:relative` にする。

## 細かい指定された仕様
- ミニメニューは z-index 1700 を基本とし、サーバー管理用は 1400（モーダルより下）に調整。
- `.mini-menu-item[disabled]` は `opacity:0.5` と `cursor:not-allowed` を適用。
- `.mini-menu-item.hidden` は `display:none` でDOMから消さずに非表示。
- 外観のみを定義し、表示ロジックは `mini_menu.js` 側に委譲する。

## その他
- `style.css` から `@import './mini_menu.css'` で読み込む。HTML側は `mini-menu` クラスを付与するのみでインラインstyleを持たない。

## 変更履歴
- 2026-01-20: ミニメニュー関連スタイルを共通化するため新規作成（`style.css` から切り出し）。
