# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

### Planned
- Webダッシュボード機能の追加
- リアルタイム監視アラート機能
- 攻撃パターンのカスタマイズ機能拡張
