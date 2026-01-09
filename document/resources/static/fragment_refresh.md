# fragment_refresh.js

対象: `src/main/resources/static/fragment_refresh.js`

## 概要
- フラグメント（HTML 断片）の自動更新を管理するクライアントモジュール。`data-auto-refresh` 属性を持つ `.fragment-root` 要素に対して定期的な取得と差し替えを行う。

## 主な機能
- 指定された秒間隔で `/api/fragment/{name}` をフェッチして断片を差し替える
- フェッチ後に必要なスクリプト（例: user_list.js 等）を動的に読み込み、初期化関数を呼び出す
- 自動更新タイマーは要素単位で管理され、再初期化時はタイマーをクリアしてから再設定する

## 挙動
- `setupFragmentAutoRefresh(root)` を呼ぶと `root` 内の `.fragment-root[data-auto-refresh][data-fragment-name]` 要素を検索して定期取得を設定する。
- 各要素の `data-auto-refresh` は秒数（整数）で指定される。
- 取得したレスポンスが HTML の場合は `.fragment-root` を抽出して対象要素の innerHTML を置換する。
- フラグメント特有のスクリプトが必要な場合は `window.loadScriptsSequential`（グローバルで提供）を呼んで読み込む。

## 細かい指定された仕様
- `data-auto-refresh` が0または指定なしの場合は自動更新を行わない。
- ネットワークやサーバエラー時は console.warn に詳細を出力し、タイマーはそのまま継続する。

## メソッド一覧
- `setupFragmentAutoRefresh(root)` - 指定 root 内の fragment の自動更新を設定

## 変更履歴
- 2026-01-09: スクリプトローダを `script.js` の `window.loadScriptsSequential` に一本化。フォールバック実装を削除。

## コミットメッセージ例
- docs(front): fragment_refresh.js の仕様を更新（loader 一本化）

