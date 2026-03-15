# dashboard.html
対象: `src/main/resources/fragments/dashboard.html`

## 概要
- ダッシュボードビューのフラグメントで、トップ画面の統計表示と主要リンクを提供する。

## 主な機能
- ダッシュボード向けHTMLレイアウトを定義し、mainテンプレートの差し替え領域として使用する。
- SPA風遷移時に読み込まれ、ナビゲーションやカードを表示。

## 挙動
- `/main?view=dashboard` で選択された際にサーバから配信され、`navigation.js` 等が `data-view="dashboard"` を見て表示する。

## 細かい指定された仕様
- ロジックは含まず、スタイルは共通CSSに依存。ID/クラスは既存JSと整合させること。

## その他
- UI変更時は関連CSS/JSへの影響確認を行う。

## 変更履歴
- 2026-03-16: ドキュメント新規作成。
