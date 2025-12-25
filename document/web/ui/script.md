# script.js（共通スクリプト仕様）

ファイル: `src/main/resources/static/script.js`

## 概要
アプリケーション全体で共通に使用されるクライアントスクリプトを格納します。グローバルなユーティリティやモジュールの初期化、メニュー操作、セキュリティに関するヘルパ等が含まれる想定です。

## 参照点（チェック）
- `script.js` が DOM の読み込みや CSP に影響を与えないように、inline event handlers を避け、`addEventListener` を利用していることを確認する
- `UserList` / `UserModal` 等のモジュールを適切に `window` に露出��ているか

## テストケース（推奨）
- 主要画面で JS エラーが発生しないこと（Console を確認）
- CSP を強化している場合でも inline イベントハンドラを使用していないこと

## 関連
- `index.html` などで script を参照するテンプレート
- `user_list.js`, `user_modal.js` と相互に利用されるグローバルユーティリティ

