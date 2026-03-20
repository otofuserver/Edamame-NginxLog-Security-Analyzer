# block_ip.js

対象: `src/main/resources/static/block_ip.js`

## 概要
- block_ip管理画面のクライアントロジック。ステータス/種別フィルターと検索、ページング一覧、手動作成モーダル、削除・無効化・有効化・編集ミニメニューを制御する。閲覧は全認証ユーザー、操作（作成/削除/無効化/有効化/編集）はオペレーターのみ（canOperateフラグで制御）。フィルター/ソート/検索状態はURLクエリに保持し、リロード後も復元する。

## 主な機能
- `/api/block-ip` からステータス/種別フィルター付きで一覧取得（20件ページング、既定ソートはIP昇順）。
- ミニメニューで無効化（POST `/api/block-ip/{id}/revoke`）、有効化（POST `/api/block-ip/{id}/activate`）、編集（PUT `/api/block-ip/{id}`）、削除（DELETE `/api/block-ip/{id}`）を提供し、どれも確認/入力モーダル経由（canOperate=true時のみ）。
- 手動作成モーダルからPOST `/api/block-ip` を送信（serviceTypeはMANUAL固定、canOperate=true時のみ実行）。対象エージェントは英数字・ハイフン・アンダースコア・ドットのみ64文字以内（空欄可、CDN名も可）。
- 対象エージェント入力には `agent_servers.agent_name` の重複排除リスト＋`cloudflare` をサジェスト表��（`GET /api/block-ip/agents`）。候補は `agent: 名前` / `CDN: cloudflare` で識別表示し、入力値は元の名前（`:` は利用不可）。初回取得で接続が不安定な場合は自動リトライする。

## 挙動
- `BlockIp.init()` でmini_menu/list_view_coreをセットアップし、60秒ごとの自動リロードは正式廃止。15秒間隔で `/api/block-ip/cleanup-status` をポーリングし、バージョンが進んだら `block-ip:cleanup-done` を発火して断片更新・一覧リロードを行う。テーブルはURL脅威度ビューと同じ共通list_viewスタイル（改行禁止・横スクロール許可・カラム幅固定）を適用。
- ステータス/種別/検索で即時リロード（既定: status=ACTIVE, serviceType=all）。ソート/フィルター/検索はURLクエリに反映し、F5後も復元。
- `/api/block-ip` はステータス未指定時も ACTIVE を既定値とし、UIとAPIでデフォルトを揃える。
- 行クリックでミニメニューを表示。無効化選択で対象IPを表示するモーダルを開き、`/api/block-ip/{id}/revoke` を呼んでREVOKED化（canOperate=true時のみ）。有効化選択で `/api/block-ip/{id}/activate` を呼び、過去の終了時刻を持つ場合は新しい終了時刻（空欄で無期限）を入力して再度 ACTIVE 化（canOperate=true時のみ）。編集選択で対象エージェント/終了時刻/理由を編集するモーダルを開き、PUT `/api/block-ip/{id}` を送信（終了時刻は空欄または未来）。削除選択は確認後にDELETE `/api/block-ip/{id}` を実行。
- 手動作成ボタンで入力モーダルを開き、バリデーション（IP・理由必須、終了時刻は現在以降または空欄）後に作成（canOperate=true時のみ）。
- 対象エージェント入力欄はフォーカス時に候補を取得し、英数字/._- 64文字以内（空欄可、`:` は不可）のバリデーションを作成・編集双方に適用。

## 細かい指定された仕様
- ページサイズ: 20（`defaultSize`）。
- ソート: 既定は `ipAddress` asc。ヘッダークリックで変更。
- ステータスフィルター: ACTIVE/EXPIRED/REVOKED(無効)/all。`all` はクエリ未設定と同義。
- 種別フィルター: MONITOR_BLOCK/APP_LOGIN/MANUAL/all。
- 依存: `list_view_core.js`, `mini_menu.js`, `loadScript`(フォールバック)が存在する前提。

## その他
- 通信エラー時はメッセージ表示またはalertで通知。未認証時は/loginへリダイレクト。
- targetAgentNameは任意。endAtはdatetime-local文字列をそのまま送信（サーバー側で変換する前提）。

## 変更履歴
- 2026-03-21: 60秒オートリロードを正式廃止し、クリーンアップポーリングのみで更新。対象エージェント候補のサジェスト取得（agent_servers + cloudflare）を追加し、候補表示を `agent:` / `CDN:` で識別。
- 2026-03-16: IP昇順を初期ソートとし、フィルター/ソート/検索状態をURLに保持するよう改善。無効化/有効化/編集操作を追加し、有効化時に新しい終了時刻（空欄で無期限）を入力できるよう変更。
- 2026-03-16: 新規作成（ブロックIP管理用JS追加）。
- 2026-03-16: サーバー選択を廃止し、serviceTypeをMANUAL固定の手動作成に変更。
- 2026-03-16: 閲覧を全認証ユーザーに開放し、操作はオペレーターのみ(canOperate)に整理。
