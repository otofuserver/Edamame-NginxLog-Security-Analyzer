# list_views.css
対象: `src/main/resources/static/list_views.css`

## 概要
- ユーザー/サーバー/URL抑止/URL脅威度のリスト系画面で共通利用するCSS。改行禁止・省略表示、ページネーション、テーブル列幅、色付けを集約。

## 主な機能
- 共通テーブル設定: `white-space: nowrap; overflow: hidden; text-overflow: ellipsis;` を各リスト th/td に付与。
- 共通ページネーションのレイアウト（flex, gap, disabled 時の半透明）。
- 各画面のカラム幅定義（ユーザー/サーバー/URL抑止/URL脅威度）。
- 非アクティブ行 (`.inactive`) の背景・文字色設定。
- URL脅威度の脅威種別色付け・バッジ・行の左罫線強調。

## 挙動
- HTML側でクラス/IDを付与すれば自動適用される（例: `.threat-col`, `.status-col`, `.modsec-col`）。
- URL脅威度: ModSec列幅は 100–110px、HTTP列は 80–90px に拡げ省略を抑制。

## 細かい指定された仕様
- ユーザ���/サーバーリストは `min-width:900px`、縦スクロール許容、`.inactive` 行は灰色。
- URL抑止: `min-width:780px` の colgroup 幅指定、`.mono` セルに mask で末尾グラデーション。
- URL脅威度: `table-layout:auto`、URLセルは mask で末尾グラデーション、各列に最小/最大幅を設定。
- ページネーションのボタンスタイルは統一され、disabled で opacity 0.5。

## その他
- インポート: `style.css` から `@import './list_views.css';` で利用。
- mini_menu.css など他CSSとは役割分担。改行禁止を共通で担う。

## 変更履歴
- 2026-01-20: 新規作成。リスト系スタイルを共通化し、URL脅威度の ModSec 列幅を 100–110px に拡張。

## コミットメッセージ例
- docs(style): add list_views.css spec
