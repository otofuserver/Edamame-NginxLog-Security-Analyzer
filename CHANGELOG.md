# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - 2025-01-21 - 緊急セキュリティ強化版

### 🚨 Security - 緊急XSS対策強化
- **WebSecurityUtilsクラス追加**: 包括的XSS攻撃検知・防御システム
  - 16種類の高精度XSS攻撃パターン検知
  - HTMLエスケープ・JSONエスケープ機能
  - SQLインジェクション検知機能
  - セキュリティヘッダー自動生成
- **全Webコントローラーのセキュリティ強化**
  - DashboardController: 全出力データのHTMLエスケープ適用
  - ApiController: JSON出力の完全サニタイズ
  - StaticResourceController: パストラバーサル攻撃防止
- **多層防御システム実装**
  - リクエスト検証（User-Agent、Referer、クエリパラメータ）
  - セキュリティヘッダー設定（CSP、XSS Protection、Frame Options）
  - 入力値サニタイズとファイル名制限

### 🔧 Database - MySQL照合順序エラー修正
- **DbSchemaクラス強化**: MySQL 8.0照合順序不一致エラーの完全解決
  - `fixCollationIssues()` メソッド追加で全テーブル照合順序を統一
  - 全文字列カラムを `utf8mb4_unicode_ci` に変更
  - `registerOrUpdateServer()` メソッドで照合順序を明示的に指定
- **DataServiceクラス修正**: サーバー一覧取得時のJOIN句で照合順序明示
  - `COLLATE utf8mb4_unicode_ci` を追加してエラー回避
  - MySQL 8.0デフォルト照合順序との混在問題解決

### Added
- XSS攻撃のリアルタイム検知・ブロック機能
- セキュリティログ出力機能（攻撃者IP・攻撃内容記録）
- CSRFトークン生成機能
- パストラバーサル攻撃防止機能
- 許可されたリソースのみアクセス可能な制限機能
- MySQL照合順序統一機能（utf8mb4_unicode_ci）

### Changed
- Webフロントエンド統合版をセキュリティ強化版に更新
- 全HTML出力にXSS対策エスケープ処理を適用
- APIレスポンスの完全サニタイズ実装
- エラーメッセージもセキュリティ対策適用
- バージョンを1.0.1に更新（セキュリティ強化＋照合順序修正版）

### Fixed
- XSS攻撃がWebフロントエンドに通る脆弱性を完全修正
- 不正なUser-AgentやRefererによる攻撃を防止
- 静的リソースへの不正アクセスを制限
- **MySQL照合順序エラーを完全解決**: `Illegal mix of collations` エラーの修正
- サーバー一覧取得エラーの解消
- データベーススキーマの照合順序統一による安定性向上
- URL脅威度APIでのNPEを防止し、取得失敗時に詳細ログを出力するように修正

## [1.0.0] - 2025-01-20

### Added
- **複数サーバー監視機能の完全実装**
  - CSV形式（管理名,ログパス）での複数サーバー設定管理
  - 各サーバーの独立したログファイル監視（LogMonitorクラス）
  - サーバー設定ファイル（servers.conf）の5分間隔自動更新チェック
  - 複数サーバー対応のデータベーススキーマ拡張
- **攻撃パターンファイル自動更新機能の完全実装**
  - 1時間ごとのGitHubからの自動バージョンチェック・更新
  - 更新前の自動バックアップ機能
  - ネットワークエラー時の適切なハンドリング
  - AttackPattern.getPatternCount()メソッドの追加
- **Webフロントエンド統合機能**
  - リアルタイムダッシュボード表示
  - REST API エンドポイント
  - 自動更新機能付きWeb UI
  - バックエンドとは独立したスレッド処理
- **タグプッシュ時の自動リリース機能**
  - v*タグプッシュ時にcontainerフォルダにJARファイルを格納
  - 完全なリリースパッケージのzip化を自動実行
  - GitHubリリースの自動作成とアセットアップロード
  - CHANGELOG.mdからリリースノートを自動生成
- **ログモニタリングの堅牢性向上**
  - ファイルローテーション検知でlastModifiedフィールドを活用
  - ファイル更新時刻の変化も考慮したより確実な検知ロジック
  - BufferedReader.skip()の戻り値チェック機能
  - ビジーウェイト軽減のためのThread.sleep間隔最適化

### Fixed
- **コード品質向上とIDEの警告解消**
  - LogMonitorのlastModifiedフィールド未使用警告の解決
  - LogParserのisValidIp()をisInvalidIp()にリネームして否定演算子を排除
  - 未使用のLOG_PATHフィールドを削除（複数サーバー対応により不要）
  - 未使用のSLF4J loggerフィールドとimportを削除
  - 重複代入警告の解消とロジック整理
- **ファイルローテーション検知の改善**
  - lastModifiedフィールドを活用したより堅牢な検知機能
  - ファイルサイズと更新時刻の両方を考慮した判定ロジック
  - 統一された更新処理による保守性向上

### Changed
- **設計の一貫性向上**
  - カスタムlog()メソッドによる統一されたログ処理
  - 複数サーバー監視アーキテクチャへの完全移行
  - コード整理により保守性とメンテナンス性を向上

### Security
- **暗号化機能の継続的強化**
  - AES-GCM暗号化によるDB接続情報の保護
  - セキュア設定ファイル作成ツールの安定稼働
  - BouncyCastle暗号化ライブラリの最新対応

### Technical
- **Java 21完全対応**
  - 最新Java機能の積極的活用
  - モジュールシステム対応とパフォーマンス最適化
- **GitHub Actions CI/CD完全実装**
  - 自動ビルド・テスト・リリース機能
  - Dockerコンテナ起動テストの実装
  - タグベースの自動バージョニング

## [Unreleased]

### Added
- URL脅威度ビューを追加し、サーバー選択と脅威度フィルタ（安全/危険/注意/不明/全件）付きで色分け表示を実装。
- feat(web): 管理者向けユーザー管理断片を追加（断片: `/api/fragment/users`、検索API: `/api/users`）。
  - 管理者ロールのみ閲覧可能（AuthenticationService / users_roles を参照して判定）。
  - 検索はサーバーサイドで実行（ユーザー名・メールアドレスで部分一致検索、ページング対応）。
  - 断片テンプレート: `src/main/resources/fragments/user_management.html` を追加。
- fix(web): Web経由の新規ユーザー登録時はデフォルトで無効（is_active=false）で作成するように変更。管理者が明示的に `enabled` を渡した場合はその値を尊重します。
- feat(auth): メール変更の所有者確認フローを追加
  - `email_change_requests` テーブルを追加し、6桁ワンタイムコードによるメール所有者確認を実装
  - API: `POST /api/me/email-change/request` (リクエスト作成) および `POST /api/me/email-change/verify` (確認コード検証)
  - フロントエンド: `profile_modal.js` に確認コードモーダルを実装し、メール変更時にコード入力を促すUIを追加
- URL脅威度一覧にミニメニュー（コピー/危険/安全/解除/理由確認）と理由入力モーダルを追加し、サーバーoperator権限で分類変更できるようにした。
- db: url_registry に最終アクセス情報カラムを追加（latest_access_time, latest_status_code, latest_blocked_by_modsec）。

### Changed
- fix(web): `WebApplication` のルーティングを更新して `/api/me` 配下のメール変更エンドポイントを `UserManagementController` に割り当て（POST の 405 回避）
- docs: ドキュメントを追記/更新
  - `document/resources/static/profile_modal.md` を追加
  - `document/com/edamame/security/db/DbSchema.md` に `email_change_requests` テーブル仕様を追加
  - `document/com/edamame/web/controller/UserManagementController.md` にメール変更フローを追記
  - `document/com/edamame/web/service/UserService.md` に `requestEmailChange` / `verifyEmailChange` 契約を追加
- `is_whitelisted` をユーザー操作で false に戻せるよう仕様を更新（危険/解除操作でホワイトリスト解除を許可）。
- url_registry の既存URL再アクセス時に `latest_access_time`/`latest_status_code`/`latest_blocked_by_modsec` を即時同期するよう AgentTcpServer・DbUpdate/DbRegistry/DbService を更新

### Fixed
- UI: URL脅威度テーブルの「脅威度」「攻撃タイプ」「メソッド」ヘッダーが折り返されないように改行禁止と最小幅を設定
- fix(web): URL脅威度ビューをサーバーレンダリングで直接開いた際（F5等）も初期化しサーバーリストが読み込まれるように修正

### Notes
- DB スキーマ自動同期 (`DbSchema.syncAllTablesSchema`) により `email_change_requests` が自動生成される想定だが、本番適用前に必ず DB のフルバックアップを取得すること。
- メール送信の監査ログと検証試行回数制限（attempts に基づくロック）を運用ルールとして推奨する。

### Commits
- docs(ui): profile_modal.js の仕様書を追加
- feat(api): メール変更所有者確認フローを追加（/api/me/email-change/*）
- docs(db): DbSchema に email_change_requests を追加
