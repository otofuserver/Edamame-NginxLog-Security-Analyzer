# url_suppression.js

対象: `src/main/resources/static/url_suppression.js`

## 概要
- URL抑止管理ビューのフロントエンドロジック。検索・フィルタ・ソート・ページング・ミニメニュー表示・モーダル編集を制御し、`/api/url-suppressions` と連携する。

## 主な機能
- サーバー一覧取得とセレクト初期化（`/api/servers?size=200`）。
- 抑止ルール一覧の検索・ソート・ページング描画。
- 行クリックでのミニメニュー表示（有効/無効切替・編集・削除）。
- 作成/編集モーダルの表示・保存・削除、チェックボックス/テキスト入力のバインド。

## 挙動
- 初期化時にサーバーセレクトをロードし、検索条件から `/api/url-suppressions` を呼び出して結果を描画。
- ソート中カラムには ▲/▼ を付与し、`STATE.sort`/`STATE.order` をトグルして再描画する。
- ミニメニューは `mini_menu.js` で生成し、クリック座標近くに `mini-menu` クラスのメニューを表示。権限に応じて項目を hidden/disabled 切替。
- モーダルは `aria-hidden`/`style.display` で開閉し、保存/削除は fetch(POST/PUT/DELETE) で実行。完了後は一覧再読み込み。

## 細かい指定された仕様
- 1ページあたり size=20、パラメータは `q`/`server`/`sort`/`order`/`page`/`size` を付与。
- 取得データに含まれる `canEdit` で編集可否を判定し、ミニメニュー項目や削除ボタンの表示を制御。
- `inactive` 行は `.inactive` クラスでグレー表示。
- 削除は confirm ダイアログで再確認してから実行。

## その他
- スタイルは `style.css`（テーブル/モーダル）と `mini_menu.css`（ミニメニュー外観）を利用し、HTMLにはインラインstyleを持たない。

## 変更履歴
- 2026-01-20: ドキュメント新規作成。ミニメニュー共通化とモーダル動作を記載。
