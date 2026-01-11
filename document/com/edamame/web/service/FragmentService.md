# FragmentService 仕様書

バージョン: 1.0.1
最終更新: 2026-01-11

対象: `src/main/java/com/edamame/web/service/FragmentService.java`

概要

- フラグメント生成サービス。
- サーバ側で返却する HTML 断片（`fragments/*.html`）を読み込み、必要に応じて動的な内容を組み込んで返却する責務を持つ。

主な機能

- `dashboardFragment(Map<String,Object> data)` : ダッシュボード断片を生成し、統計カード／サーバ一覧／最近のアラート等を組み込む。
- `testFragment()` : テスト用断片を返却する（自動更新無効）。

挙動

- 各フラグメントは `class="fragment-root"` でラップされ、`data-auto-refresh` 属性でクライアント側の自動更新間隔を制御する（例: dashboard は 30 秒）。
- テンプレートが見つからない場合は安全なフォールバック HTML を返す。
- 入力の Map/List は null 安全に取り扱い、不正な型の場合は該当部分をスキップする。

細かい指定された仕様

- 断片のルート要素は `data-fragment-name` を持つことが推奨される（フロントの再取得に使用）。
- 出力 HTML 内のユーザー入力や URL などは `WebSecurityUtils` のサニタイズユーティリティを使って XSS を防ぐ。
- `dashboardFragment` の自動更新は断片単位で制御し、グローバルなページ全体の自動更新スクリプトは無効化する（`{{AUTO_REFRESH_SCRIPT}}` は空にする）。
- サーバ一覧の "本日のアクセス数" は複数のキー名に対応: `todayAccessCount`, `today_access_count`, `todayAccess`, `totalAccess` の順で探して使用する。
- 最近のアラート表示は URL 表示専用の `sanitizeUrlForDisplay` を利用し、表示上の可読性（`/` をエスケープ解除）を担保する。
- 攻撃タイプ別統計はダッシュボードから削除（表示しない）。必要なら管理画面や専用レポートで提供する。

存在するメソッドと機能

- `public String dashboardFragment(Map<String, Object> data)`
  - ダッシュボード断片を生成して返す。データが null の場合はフォールバックを返却。
- `public String testFragment()`
  - テスト用断片を返す。
- 内部ユーティリティ: `safeInt(Map<String,Object>, String)`、`toInt(Object)` など数値取得ヘルパを提供。

その他

- フラグメント追加の際は `fragments/{name}.html` にテンプレートを置き、`FragmentService` にメソッドを追加して返却すること（テンプレートプレースホルダは `{{CONTENT}}` などを利用）。

変更履歴

- 2025-12-17: 初期仕様作成。 
- 2026-01-06: `dashboardFragment` をリッチ化（統計カード/サーバ一覧/最近のアラート）
- 2026-01-10: アラートURLの二重エスケープ問題を修正（`sanitizeUrlForDisplay` を利用するよう変更）
- 2026-01-11: 攻撃タイプ別統計をダッシュボードから除外（集計範囲が他と一致しないため）

コミットメッセージ例

- docs(service): FragmentService 仕様を更新（ダッシュボード強化・URL 表示修正・攻撃タイプ統計除外）
