# FragmentService 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/service/FragmentService.java`

責務:
- HTML 断片（fragments/*.html）を読み込み、必要に応じてサーバ側でラップして返す。
- クライアントの AJAX ナビゲーション（`/api/fragment/*`）に対して HTML 断片を返却する。これにより右側コンテンツのみ差し替えてページ遷移を実現する。

主要メソッド:
- `dashboardFragment(Map<String,Object> data)` : ダッシュボード断片を生成（`data-auto-refresh` = 30 を付与）
- `testFragment()` : テスト用断片を返す（`data-auto-refresh` = 0）

仕様:
- 断片のルート要素は必ず `class="fragment-root"` を持ち、`data-auto-refresh` 属性で自動更新秒数を指定する。
- `data-auto-refresh="0"` は自動更新無効を表す。

エラー処理:
- テンプレートファイル読み込みに失敗した場合はフォールバックの安全な HTML を返す。

拡張:
- 断片を追加する際は `fragments/` に HTML を配置し、`FragmentService` にメソッドを追加して返却すること。
