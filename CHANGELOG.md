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
  - StaticResourceController: パストラバーサル攻撃���止
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
- **設計���一貫性向上**
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

### Changed
- DB: `action_tools` / `action_rules` のスキーマ互換性対応を実施（2025-11-27）
  - `action_tools` に `command_template` (TEXT) を追加
  - `action_tools.tool_type` に `DEFAULT 'shell'` を設定して初期データとの互換性を確保
  - `action_rules` に `condition_expression` (TEXT)、`is_active` (BOOLEAN DEFAULT TRUE)、`description` (TEXT) を追加
  - `DbInitialData` の初期データ挿入処理を互換化（テーブルのカラム存在チェックを行い、存在するカラムのみでINSERTする方式に改修）

### Notes
- この変更は起動時の "Unknown column" や "Field ... doesn't have a default value" のエラーを解消する目的で行われました。
- スキーマ自動同期 (`DbSchema.syncAllTablesSchema`) により既存DBに不足しているカラムが自動追加されることを想定しています。ステージングでの十分な確認と本番移行前のDBバックアップを必ず行ってください。

### 2025-12-17 - ドキュメント追加とDB接続耐障害性の改善
- docs(web): `document/web` に Web パッケージ仕様書を追加（ダッシュボード/断片/認証/コントローラ/サービス等の仕様）
- fix(db): `DbSession.ensureConnected()` を追加し、`DbService.getConnection()` が接続の健全性を確認して必要に応じて再接続を試行するように変更（最大リトライ既存設定に従う）
- fix(auth): 認証処理のログ記録を堅牢化（`insertLoginHistory` をユーザーID取得→INSERT の方式に変更）および SQLException 詳細ログ出力を追加

### Notes
- 上記の DB 側の改善により、MySQL 再起動等で既存 Connection が切断された場合でも自動で再接続を試み、アプリケーションの一時的な障害を低減します。長時間の停止や継続的な接続障害に対しては追加の監視・アラート設定を推奨します。
- ドキュメント追加は `document/web` に格納されています。`document/` が .gitignore に設定されている場合は、必要なファイルを強制追加(-f)してコミット済みです。
