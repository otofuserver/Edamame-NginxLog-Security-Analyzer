# Web パッケージ仕様書（概要）

バージョン: 1.0.0
最終更新: 2025-12-17

対象:
- `src/main/java/com/edamame/web` 配下の全クラス

目的:
- Web アプリケーションの責務、主要コンポーネントの役割、公開エンドポイント、セキュリティ要件、フラグメント更新の動作を仕様として明確化する。

主要コンポーネント
- `WebApplication.java` : アプリケーション起動/設定のエントリ（存在する場合）。
- `config` : `WebConfig.java` 等、HTML テンプレート・静的リソースの一元管理。CSP ヘッダー生成やメニュー HTML の管理を行う。
- `controller` : HTTP リクエストを受けるコントローラ群（ダッシュボード、API、ログイン/ログアウト、静的リソース）。
- `service` : ��ジネスロジックとデータ取得を担うサービス（`DataService`, `FragmentService`）。
- `security` : 認証・XSS/入力検査・CSP 付与などセキュリティ関連ユーティリティとフィルタ。

重要な設計方針
- フラグメント単位での表示更新を優先: 各断片（fragment）に `data-auto-refresh` 属性を付与して自動更新を制御する。
- ページ全体の自動リロードは原則禁止（`AUTO_REFRESH_SCRIPT` はフラグメント制御が優先される）。
- CSP を厳格運用: `script-src` に nonce を付与してインラインスクリプトの実行制御を行う（必要最小限のインライン許可のみ）。
- 仕様変更時はドキュメント（document/web）と CHANGELOG.md を更新すること。

運用例
- 新しいページを追加する場合: `FragmentService` に断片を追加し、`WebConfig#getMenuHtml()` にメニュー項目を追加する。UI 設計の観点でフラグメントは `fragments/*.html` に置き、`FragmentService` でラップして返却する。

注記: 本仕様書は `document/db/DbDelete.md` の表現スタイルを参考に作成しています。
