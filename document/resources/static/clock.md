# clock.js

対象: `src/main/resources/static/clock.js`

## 概要
- クライアントサイドの軽量時計モジュール。ページ内の `.current-time` 要素を定期的に更新する。単責任で、表示フォーマットと起動制御のみを提供する。

## 主な機能
- 日時の整形（`formatYMDHMS`）
- 時計の起動・重複起動防止（`startClock`）

## 挙動
- `startClock()` を呼ぶと即座に現在時刻を `.current-time` 要素に反映し、以後1秒ごとに更新する。
- 既に起動済みであれば多重起動を行わない（`window.__edamame_clock_started__` フラグで管理）。
- 例外は内部でキャッチして `console.warn` に出力する。

## 細かい指定された仕様
- 表示フォーマットは `yyyy-MM-dd HH:mm:ss`（`formatYMDHMS` により生成）。
- DOM 更新時に例外が発生してもループを止めない（個別要素の更新は try/catch で保護）。
- グローバル公開:
  - `window.startClock` 関数
  - `window.formatYMDHMS` 関数

## その他
- テスト: DOM に `.current-time` 要素を配置して `startClock()` を呼び、1秒後に要素のテキストが更新されることを確認する簡易テストが有効。

## 変更履歴
- 2026-01-09: 新規作成（スクリプト分割に伴い独立モジュール化）

## コミットメッセージ例
- docs(front): clock.js の仕様書を追加

