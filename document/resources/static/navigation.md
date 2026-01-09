# navigation.js

対象: `src/main/resources/static/navigation.js`

## 概要
- クライアント側のページ遷移（断片ベース）を担当するモジュール。`navigateTo(view, push)` を公開し、`main-content` に対してフラグメントを取得・挿入する。

## 主な機能
- 指定 view のフラグメントを `/api/fragment/{view}` から取得して描画
- 必要に応じてフラグメント固有のスクリプトをロードして初期化関数を呼び出す
- history API を使ってブラウザの戻る/進むをサポート

## 挙動
- HTML レスポンスなら `main` 要素に挿入し、`setupFragmentAutoRefresh` をトリガする。
- `users` ビューや `servers` ビューなど、フラグメントに依存するスクリプトを `window.loadScriptsSequential` で読み込み、初期化を実行する。
- `push=true` の場合は `history.pushState` で URL を更新する。

## 細かい指定された仕様
- 取得時に 401 を受けたら `/login` にリダイレクト。
- フラグメントの content-type が `text/html` でない場合は JSON と見なし整形表示する。

## メソッド一覧
- `navigateTo(view, push=true)` - 指定 view のフラグメントを取得して描画

## 変更履歴
- 2026-01-09: 新規分割で追加

## コミットメッセージ例
- docs(front): navigation.js の仕様書を追加

