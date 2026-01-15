# url_threat.html

対象: `src/main/resources/fragments/url_threat.html`

## 概要
- URL脅威度ビューの断片HTML。URL一覧テーブル、検索/フィルタ、ミニメニューを配置し、`url_threat.js` と `mini_menu.js` で動的動作を���供する。
- データは `url_registry` の `latest_*` カラムを用いて更新済みの最新情報を表示する。

## 主な機能
- URL一覧テーブルの表示（サーバー選択、脅威度フィルタ、キーワード検索に対応）。
- 行クリックでミニメニューを表示（コピー/危険/安全/解除/理由確認）。
- 理由入力用モーダル（危険・安全・解除・理由確認）を埋め込み。

## 挙動
- ページロード時に `url_threat.js` がデータ取得とテーブル描画を実施。
- 行クリックで `mini_menu.js` を用いた共有ミニメニューを出現させ、外側クリックで閉じる。
- 変更系操作は operator 以上の権限がない場合は無効化される。
- `is_whitelisted` / `user_final_threat` 状態に応じてメニュー項目を表示/非表示。

## 細かい指定された仕様
- データソースは `url_registry` の `latest_access_time`/`latest_status_code`/`latest_blocked_by_modsec` を直接利用し、`access_log` への依存はない。
- テーブルソートは脅威度優先度→最新アクセス時刻の順で `url_threat.js` 側が実施。
- モーダルは理由入力必須で送信し、送信後は再読込で最新状態を反映。

## その他
- ミニメニューのスタイル/位置計算は `mini_menu.js`（共通パーツ）に委譲。

## 変更履歴
- 2026-01-15: URL脅威度ビューの仕様書を追加。`url_registry` 最新メタのみで一覧を構成する仕様に更新。
