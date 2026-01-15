# url_threat.js

対象: `src/main/resources/static/url_threat.js`

## 概要
- URL脅威度ビュー専用のフロントエンドロジック。`url_registry` の最新メタデータを取得し、テーブル描画・ミニメニュー操作・分類更新モーダルを制御する。

## 主な機能
- URL一覧取得と描画（サーバー選択・脅威度フィルタ・検索に対応）。
- 行クリック時に `mini_menu.js` を使ってミニメニューを表示（コピー/危険/安全/解除/理由確認）。
- 危険/安全/解除/理由確認モーダルの表示・送信（`user_final_threat` / `is_whitelisted` / `user_threat_note` を更新）。
- 最新アクセスメタ（`latest_access_time`/`latest_status_code`/`latest_blocked_by_modsec`）の表示とソート。
- 外側クリックや別行クリックでミニメニューを閉じる再入可能なリスナー処理。

## 挙動
- 初期化で必要なフラグメントを読み込み、サーバー一覧とURL一覧を順に取得して描画。
- ミニメニューはクリック座標周辺に表示し、既存メニューがあれば一旦閉じてから再表示。
- コピー操作はクリップボードAPIでURLをコピーし、通知の出し方を見直した軽量なフィードバックを表示。
- 分類変更時は権限チェックにより操作不能状態をスタイルで示す。

## 細かい指定された仕様
- データソースは `url_registry` の `latest_*` カラムに統一（access_log への依存なし）。
- `user_final_threat`=true で危険表示、`is_whitelisted`=true で安全表示、いずれもfalseの場合は最新メタとattack_typeに応じて caution/unknown を判定。
- メニュー表示条件: 危険化は既に user_final_threat=true の場合非表示、安全化は is_whitelisted=true の場合非表示、解除はどちらかが true の場合のみ表示。
- 理由確認は operator 以上が編集可、その他は read-only。

## その他
- ミニメニュー位置/クローズ挙動は共通の `mini_menu.js` に委譲。
- スタイルは `style.css` の `.sidebar-mini-menu` 系を利用し、`navigation.js` でメニュー呼び出しを統合。

## 変更履歴
- 2026-01-15: URL脅威度ビュー用スクリプトを追加。`url_registry` 最新メタのみで一覧を構成し、共通ミニメニューを採用。
