# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

### Changed
- **データベーススキーマの複数サーバー対応**
  - access_log, url_registry, modsec_alertsテーブルにserver_nameカラム追加
  - サーバー管理用のserversテーブル新規作成
  - サーバー別のデータ識別とパフォーマンス向上のためのインデックス追加
- **外部ネットワークアクセス制限の廃���**
  - バックエンドでのGitHub連携を有効化
  - AttackPattern.updateIfNeeded()の実際のHTTP通信実装
  - より堅牢なエラーハンドリングとログ出力

### Fixed
- **複数サーバー環境での重複URL登録問題の解決**
  - サーバー別のURL一意性確保（method + full_url + server_name）
  - サーバー情報の自動登録・更新機能
- **サーバー設定ファイル更新検知の誤報を修正**
  - updateIfNeededメソッドで静的変数による最終更新時刻管理を実装
  - 初回チェック時の誤検知を防止
  - ファイルが実際に更新された場合のみ処理を実行
- **GitHubワークフローエラーの修正**
  - Unix/Linux用gradlewスクリプトファイルの不足を解決
  - CI/CD環境でのビルドプロセス正常化

### Removed
- **単一サーバーモード（フォールバック）の完全廃止**
  - 複数サーバー監視のみに統一
  - 使用されなくなったmonitorLogFile()とprocessLogLine()メソッドを削除
  - 未使用インポート（java.io.*）のクリーンアップ
  - コメント内の「フォールバック」表現を「代替」に統一
- 古い外部ネットワークアクセス制限に関するドキュメント記載
- 実装済みTODOコメントの削除

## [1.0.33] - 2025-07-17

### Changed - 🚀 MAJOR ARCHITECTURE MIGRATION
- **Python版からJava版への完全移行**
  - メインアプリケーション: nginx_log_to_mysql.py → NginxLogToMysql.java
  - セットアップツール: setup_secure_config.py → SetupSecureConfig.java
  - ビルドシステム: Maven → Gradle
  - 実行環境: Python 3.11 → Java 21+
  - 暗号化: Python cryptography → BouncyCastle AES-GCM

### Added
- **Java版新機能**
  - ModSecurity処理ロジックの改善（2重計上問題の解決）
  - データベーススキーマ自動更新機能
  - バージョン非依存のセットアップスクリプト
  - Gradle Shadow JARによる独立実行可能なJARファイル生成

### Fixed
- ModSecurity 2重計上問題を解決
  - ModSecurity詳細行は一時保存のみ
  - HTTPリクエスト行のみをアクセスログとして保存
  - 旧システムのプロセスに従った正しい処理フローを実装
- modsec_alertsテーブルのカラム不足問題を解決
  - messageとcreated_atカラムの自動追加機能
  - 既存テーブル構造の自動更新

### Removed - 🗑️ CLEANUP
- **削除されたPython版ファイル**:
  - nginx_log_to_mysql.py
  - setup_secure_config.py  
  - modules/ フォルダ
  - .venv/ フォルダ
- **削除されたMaven関連ファイル**:
  - pom.xml
  - target/ フォルダ

### Technical Details
- **新しいプロジェクト構成**:
  - src/main/java/com/edamame/security/ (メインパッケージ)
  - src/main/java/com/edamame/tools/ (ツールパッケージ)
  - build.gradle (Gradle設定)
  - build/libs/ (JAR生成先)
- **Docker環境更新**:
  - ベースイメージ: python:3.11-slim → openjdk:21-jdk-slim
  - JARファイルパス修正: build/libs/から正しくコピー
- **スクリプト改善**:
  - setup_secure_config.bat/sh でバージョン自動検索機能追加

---

## Python版変更履歴 (v1.0.32以前)

### [1.0.36] - 2025-07-16

### Fixed
- log_parser.pyの例外処理警告を完全修正
  - 378行目extract_query_params関数: except Exceptionを(ValueError, AttributeError, TypeError)に変更
  - 403行目normalize_url関数: except Exceptionを(ValueError, AttributeError, TypeError)に変更
  - 417行目decode_url関数: except Exception as eを(UnicodeDecodeError, ValueError, TypeError)���変更し未使用変数eを削除
  - extract_query_params関数にlog_funcパラメータを追加してlog_func未定義エラーを防止

### Improved
- エラーハンドリングの具体化
  - URL処理関連の例外を適切な型に分類
  - デバッグ時のエラー情報をより詳細に出力
  - 関数間のログ出力一貫性を向上

## [1.0.35] - 2025-07-16

### Fixed
- attack_pattern.pyの残存警告とエラーを完全修正
  - 77行目、520行目、531行目の`except Exception:`を具体的なエラータイプに変更
  - detect_attack_type関数にlog_funcパラメータを追加し、74-75行目の未解決参照エラーを解消
  - nginx_log_to_mysql.pyの関数呼び出し箇所（233行目、634行目）も更新
  - フォールバック関数のパラメータも統一

### Improved
- 例外処理の精度をさらに向上
  - mysql.connector.Error、ValueError、TypeError、IOError、OSError、UnicodeErrorを適切に分類
  - 各エラータイプに応じた詳細なログメッセージとハンドリング
  - エラー時の自動クリーンアップ処理の信頼性向上

### Added
- ログ関数パラメータの一貫性確保
  - 全ての攻撃パターン関連関数でlog_funcパラメータを統一
  - モジュール間でのログ出力の一貫性を向上

## [1.0.34] - 2025-07-16

### Fixed
- attack_pattern.pyの例外処理を改善
  - 5箇所の`except Exception:`を具体的なエラータイプに変更
  - decode_url関数: UnicodeDecodeError, ValueErrorを明示的に処理
  - detect_attack_type関数: FileNotFoundError, PermissionError, json.JSONDecodeError, IOError, OSErrorを分離処理
  - get_current_version関数: ファイルアクセスとJSON解析エラーを分類
  - validate_attack_patterns_file関数: 各エラータイプに応じた適切なFalse返却
  - run_comprehensive_test関数: mysql.connector.Error, ファイルI/Oエラーを個別処理

### Improved
- エラーハンドリングの精度向上
  - 各エラータイプに応じた詳細なログメッセージを追加
  - データベース関連エラーとファイルI/Oエラーの分離
  - エラー時の自動クリーンアップ処理を強化

### Added
- mysql.connectorモジュールのインポートを追加（mysql.connector.Error使用のため）

## [1.0.33] - 2025-07-16

### Fixed
- ログローテート後のログ取得問題を修正
  - ログローテーション検知を強化（inode変更とファイルサイズ減少の両方を検知）
  - ローテート後の新しいログファイルの内容を適切に読み込むよう修正
  - ファイル位置の適切なリセット処理を追加
  - ローテート後の処理状況を詳細にログ出力

### Improved
- ログ監視の信頼性向上
  - ローテーション検知の精度向上
  - 処理結果の統計情報表示を追加
  - エラーハンドリングの強化

## [1.0.32] - 2025-07-15

### Changed
- specification.txtの変更履歴をすべてCHANGELOG.mdに移行
  - specification.txtから詳細な変更履歴セクションを削除し、仕様書として整理
  - 変更履歴管理をCHANGELOG.mdに一元化
  - specification.txtにCHANGELOG.mdへの参照を追加

### Improved
- ドキュメント構造の明確化
  - specification.txt: 技術仕様・データベース構造・機能説明に特化
  - CHANGELOG.md: 開発履歴・変更管理に特化
- プロジェクト管理の効率化とメンテナンス性向上

## [1.0.31] - 2025-07-15

### Added
- プロジェクト直下にCHANGELOG.mdを新規作成
  - Keep a Changelog形式とSemantic Versioningに準拠した詳細な変更履歴管理
  - specification.txtとの相互補完によるドキュメント体系の強化
- 開発者向けガイドラインと貢献ルールを明文化

### Improved
- プロジェクトの変更管理とトレーサビリティを大幅向上
- Added/Changed/Fixed/Improvedで分類した構造化された履歴管理

## [1.0.30] - 2025-07-15

### Added
- ロール管理システムのヘルパー関数を追加
  - `get_user_role()`: ユーザーのロール情報を取得
  - `update_user_role()`: ユーザーのロールを更新
  - `list_all_roles()`: すべてのロール一覧を取得
  - `get_users_with_roles()`: ユーザー一覧とロール情報を取得

### Changed
- nginx_log_to_mysql.py: config.pyからの重複定義を削除し設定管理を一元化
  - APP_NAME、APP_VERSION、APP_AUTHORの重複定義を削除
  - config.pyからのインポートを優先し、事前定義の重複を解消
  - インポートエラー時の最小限のフォールバック処理のみ保持
- modules/db_schema.py: usersテーブル作成時にrole_idカラムと外部キー制約を含めるよう修正
- modules/attack_pattern.py: expected_attacks変数のスコープ問題を修正

### Fixed
- nginx_log_to_mysql.py: PEP8準拠の4スペースインデントに統一してインデントエラーを解消
- modules/attack_pattern.py: 「ローカル変数 'expected_attacks' は代入の前に参照される可能性があります」警告を解消
- 変数の重複定義による警告の完全解消

### Improved
- 設定の一元管理により保守性とコードの簡潔性を向上
- ロール管理システムの一貫性向上
- コードの可読性と保守性を向上

## [1.0.29] - 2025-07-15

### Added
- modules/log_parser.py: nginxエラーログのフィルタリング機能を大幅強化
  - より包括的なエラーパターンマッチングを実装（"*29501 open()", "connect() failed"等を追加）
  - syslog形式のnginxエラーログを専用正規表現で確実に検出・スルー
  - アクセスログでない行（リクエスト情報を含まない行）を自動判定してスルー

### Changed
- エラーログのスルー時に詳細なデバッグ情報を出力（80文字まで表示）

### Fixed
- 「ログ行のパースに失敗しました」警告の大量出力を防止し、ログの可読性を大幅向上

## [1.0.28] - 2025-07-15

### Added
- modules/db_schema.py: ロール管理システムの実装
  - rolesテーブルを新規作成：role_name（ロール名）、description（説明）、作成・更新日時
  - 初期ロール自動追加：administrator（管理者）、monitor（監視メンバー）
  - usersテーブルにrole_idカラム追加：ロールとの関連付け、外部キー制約設定
  - 初期ユーザー(admin)に自動で管理者ロールを設定
  - 既存のadminユーザーにも管理者ロールを自動付与する移行処理を実装

### Changed
- データベース初期化時にロール・ユーザー管理システムが自動構築される

## [1.0.27] - 2025-07-15

### Added
- modules/log_parser.py: file openエラーなどの処理不要なエラーログを自動スルーする機能を追加
- failed (2: No such file or directory)など、ファイルアクセスエラーを自動検出してスキップ

### Changed
- パターンマッチングロジックを修正し、エラーログとアクセスログの処理を明確に分離
- ログレベルを最適化（DEBUGレベルでスルー情報を出力）

### Fixed
- 不要なパースエラーの大量出力を防止し、ログの可読性を向上

## [1.0.26] - 2025-07-14

### Added
- attack_patterns.json: バージョンをv1.2に更新し、攻撃パターンを大幅に強化
  - CommandInjection: rmなど追加コマンドとより多くの演算子（|, &&, ||, ;, $(), ${}など）に対応
  - 新たな攻撃タイプを追加: PathTraversal, LDAP, NoSQL, TemplateInjection
  - SQLi, XSS, LFI, RFI, SSRF, XXE, CSRF, OpenRedirectの検出パターンも改善
- modules/attack_pattern.py: URLデコード機能を実装し、エンコードされた攻撃も検出可能
  - decode_url関数: 二重エンコーディング・プラスエンコーディングにも対応
  - detect_attack_type関数: 元URLとデコード後URL両方で攻撃パターン検査を実行
  - より正確な正規表現マッチングとフォールバック処理を実装
- テスト機能の大幅改善:
  - display_test_results_table関数: テスト結果を詳細なテーブル形式で表示
  - run_comprehensive_test関数: 包括��なテスト実行とクリーンアップを自動化
  - --run-testオプション実行時の自動終了とホスト復帰機能を追加
  - 成功/失敗統計とPASS/FAILステータス表示機能

### Changed
- nginx_log_to_mysql.py: URLデコード機能をメイン処理に統合
  - add_registry_entry, add_access_log関数でURLをデコード後に保存
  - エンコード例: %3Cscript%3E → <script> と���て保存し可読性を向上

---

## バージョン管理について

- このプロジェクトは [Semantic Versioning](https://semver.org/) に従います
- 主要な変更は必ず`specification.txt`にも記録されます
- バージョン番号は`config.py`の`APP_VERSION`と同期されます

## 貢献ガイドライン

1. **変更を行う前に**: 必ず最新のバージョンを確認してください
2. **変更後**: このCHANGELOG.mdと`specification.txt`の両方を更新してください
3. **コミット**: 明確で説明的なコミットメッセージを記述してください

## リンク

- [プロジェクト仕様書](./document/specification.txt)
- [README](./README.md)
- [ライセンス](./LICENSE)
