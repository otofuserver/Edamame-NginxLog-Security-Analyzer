# DashboardController 仕様書

バージョン: 1.0.0
最終更新: 2025-12-17

対象: `src/main/java/com/edamame/web/controller/DashboardController.java`

責務:
- ダッシュボード画面（`/main` など）への GET リクエストを処理し、ダッシュボードの断片（統計、アラート、サーバ一覧等）を組み合わせて HTML を返す。
- 認証済みユーザーのみアクセスを許可する（`username` を exchange 属性から取得）。
- CSP nonce を生成して `SECURITY_HEADERS` に埋め込み、テンプレートに注入する。

主要フロー
1. GET リクエスト受信
2. 認証情報チェック（`username` が設定されていなければ `/login` にリダイレクト）
3. セキュリティヘッダ適用（`applySecurityHeaders`）
4. `DataService#getDashboardStats()` を呼び出してダッシュボードデータを取得
5. `generateSecureDashboardHtml()` でテンプレートにデータを埋め込み、HTML を返却

自動更新の扱い
- グローバルなページ全体リロード（`location.reload()`）はコントローラ側で無効化済み（`{{AUTO_REFRESH_SCRIPT}}` を空文字で置換）。
- フラグメント単位の自動更新は `FragmentService` とフロントエンド JS（`script.js`）で制御する。

エラー処理
- 不正なリクエスト、DB 接続エラー等は適切なステータス（400/503/500）でエラーページを返す。

拡張ポイント
- ダッシュボードに追加コンポーネントを加える場合は `generateSecure*Html` 系メソッドを追加してテンプレートに挿入する。

## 2025-12-29 更新: サーバレンダ時の view 埋め込みとクライアント初期化の整合性

- 変更概要:
  - `handleDashboard()` でクエリパラメータ `view` を解析して `dashboardData.put("currentView", currentView)` を設定するようにしました。
  - `generateSecureDashboardHtml()` でテンプレートの `{{CURRENT_VIEW}}` を置換し、最終HTML の `#main-content` に `data-view` 属性が埋め込まれるようにしました。

- 理由:
  - ログイン遷移や F5 等のフルリロード時に、サーバがレンダリングしたページでもクライアントが初期化情報を認識できるようにするため。

- 影響範囲:
  - `WebConfig.getDashboardTemplate()` の `{CURRENT_VIEW}` を置換する実装が必須となりました。

- テスト手順:
  1. `/main?view=dashboard` および `/main?view=users` にアクセスし、返却 HTML の `#main-content` に適切な `data-view` が設定されていることを確認。  
  2. クライアントサイドの `script.js` が `data-view` を読み取り、それに応じた断片初期化（usersビューなら `user_list.js` の読み込み）が行われるかを確認。
