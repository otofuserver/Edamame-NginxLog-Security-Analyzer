# Edamame NginxLog Security Analyzer

> **🚀 Java版に完全移行しました！**  
> NGINXログ監視・セキュリティ分析ツールのJava実装版です。  
> フロントエンドは公式のもの（例：edamame-frontend）を利用しても、独自開発のものを利用しても構いません（ただし動作保証はありません）。

[![Java Build & Test](https://github.com/your-username/edamame-nginx-analyzer/actions/workflows/java-build-test.yml/badge.svg)](https://github.com/your-username/edamame-nginx-analyzer/actions/workflows/java-build-test.yml)
[![Java](https://img.shields.io/badge/Java-21+-blue.svg)](https://www.oracle.com/java/)
[![Gradle](https://img.shields.io/badge/Gradle-8.5+-green.svg)](https://gradle.org/)
[![Docker](https://img.shields.io/badge/Docker-Ready-blue.svg)](https://www.docker.com/)

## 概要
Edamame NginxLog Security Analyzerは、NGINXのアクセスログをリアルタイムで監視し、MySQLデータベースに記録・分析するJava製のセキュリティ監査ツールです。ModSecurityによるブロックや攻撃パターンの自動識別、ホワイトリスト管理など、Webサーバのセキュリティ運用を強力にサポートします。

## 🎯 主な機能
- **NGINXアクセスログのリアルタイム監視・DB記録**
- **ModSecurityブロック検知と詳細アラート記録**
- **攻撃パターン（SQLi, XSS, LFI等）の自動識別**
- **攻撃パターンファイルの1時間ごと自動更新**（GitHubから最新版を取得）
- **URLごとのホワイトリスト管理**
- **Docker対応・セキュアなDB接続情報管理**
- **データベーススキーマ自動更新**

## 🏗️ アーキテクチャ
- **言語**: Java 21+
- **ビルドツール**: Gradle
- **データベース**: MySQL 8.x
- **暗号化**: BouncyCastle AES-GCM
- **コンテナ**: Docker (OpenJDK 21)

## 📁 ディレクトリ構成
```
.
├── src/main/java/                 # Java版ソースコード
│   ├── com/edamame/security/      # メインパッケージ
│   │   ├── NginxLogToMysql.java   # メイン監視・記録クラス
│   │   ├── DbSchema.java          # データベーススキーマ管理
│   │   ├── ModSecHandler.java     # ModSecurity検知・解析
│   │   ├── LogParser.java         # NGINXログパーサー
│   │   └── AttackPattern.java     # 攻撃パターン識別
│   └── com/edamame/tools/         # ツールパッケージ
│       └── SetupSecureConfig.java # DB接続情報暗号化設定ツール
├── container/                    # 🆕 Docker・設定ファイル
│   ├── attack_patterns.json      # 攻撃パターン定義ファイル
│   ├── docker-compose.yml        # Docker Compose設定
│   ├── Dockerfile                # Dockerビルド用ファイル
│   ├── setup_secure_config.bat   # Windows用セットアップスクリプト
│   ├── setup_secure_config.sh    # Linux用セットアップスクリプト
│   ├── build-for-container.sh    # Linux用ビルドスクリプト
│   └── build-for-container.bat   # Windows用ビルドスクリプト
├── build.gradle                  # Gradle設定ファイル
├── build/libs/                   # ビルド成果物（JAR）
└── document/
    └── specification.txt         # 技術仕様書
```

## 🚀 セットアップ方法

### 前提条件
- **Java 21+** がインストールされていること
- **MySQL 8.x** が稼働していること
- **Docker & Docker Compose**（コンテナ実行の場合）

### 1. プロジェクトのビルド
```bash
# Gradleでビルド実行
./gradlew build

# JARファイル生成確認
ls build/libs/
# → NginxLogToMysql-1.0.33.jar
# → SetupSecureConfig-V1.0.jar
```

### 2. DB接続情報の暗号化設定
```bash
# Windows
container/setup_secure_config.bat

# Linux/Unix
./container/setup_secure_config.sh

# 直接実行
java -jar build/libs/SetupSecureConfig-V1.0.jar
```

### 3. アプリケーションの実行

#### ローカル実行
```bash
# メインアプリケーションを直接実行
java -jar build/libs/NginxLogToMysql-1.0.33.jar

# 環境変数でパスを指定して実行
LOG_PATH="/var/log/nginx/nginx.log" \
SECURE_CONFIG_PATH="/run/secrets/db_config.enc" \
KEY_PATH="/run/secrets/secret.key" \
java -jar build/libs/NginxLogToMysql-1.0.33.jar
```

#### Docker実行
```bash
# Docker Composeで実行（推奨）
docker compose up -d --build

# ログ確認
docker logs -f nginxlog-LO

# コンテナ停止
docker compose down
```

### 4. 事前準備（Dockerユーザー権限の調整）

1. **ホストサーバーでsyslogユーザーのUID/GIDを確認**
   ```bash
   id syslog
   ```
   例: `uid=107(syslog) gid=113(syslog) ...`

2. **Dockerfileのユーザー作成部分を、上記で確認したUID/GIDに合わせる**
   ```dockerfile
   # Dockerfileの該当箇所
   RUN groupadd -g 113 nginxlog && useradd -u 107 -g 113 -M -s /usr/sbin/nologin nginxlog
   ```

3. **（推奨）ホストサーバー上のプロジェクトフォルダの所有者をsyslogユーザーに変更**
   ```bash
   sudo chown -R syslog:syslog /path/to/Edamame\ NginxLog\ Security\ Analyzer
   ```

## 📊 データベース構造

### 主要テーブル
- **`access_log`**: NGINXアクセスログの記録
- **`url_registry`**: 検出されたURLの管理とホワイトリスト設定
- **`modsec_alerts`**: ModSecurityアラートの詳細記録
- **`users`**: フロントエンド認証用ユーザー管理
- **`roles`**: ロール管理（administrator, monitor）
- **`settings`**: システム設定

詳細なテーブル構造は [specification.txt](./document/specification.txt) を参照してください。

## 🔧 設定ファイル

### attack_patterns.json
攻撃パターン識別用の設定ファイルです。以下の攻撃タイプに対応：
- **SQLi** (SQL Injection)
- **XSS** (Cross-Site Scripting)
- **LFI/RFI** (File Inclusion)
- **Command Injection**
- **Path Traversal**
- **SSRF**, **XXE**, **CSRF** など

### 環境変数
| 変数名 | デフォルト値 | 説明 |
|--------|-------------|------|
| `LOG_PATH` | `/var/log/nginx/nginx.log` | NGINXログファイルパス |
| `SECURE_CONFIG_PATH` | `/run/secrets/db_config.enc` | 暗号化DB設定ファイル |
| `KEY_PATH` | `/run/secrets/secret.key` | 暗号化キーファイル |
| `ATTACK_PATTERNS_PATH` | `/run/secrets/attack_patterns.json` | 攻撃パターン定義ファイル |
| `NGINX_LOG_DEBUG` | `false` | デバッグログ出力の有効/無効 |

## 🛠️ 開発・メンテナンス

### ビルドとテスト
```bash
# クリーンビルド
./gradlew clean build

# テスト実行
./gradlew test

# 依存関係の確認
./gradlew dependencies

# JARファイルのみ再生成
./gradlew jar
```

### ログ監視
```bash
# Dockerコンテナのログ監視
docker logs -f nginxlog-LO

# アプリケーションレベルのデバッグログ有効化
NGINX_LOG_DEBUG=true docker compose up
```

## 🚨 トラブルシューティング

### よくある問題と解決方法

#### 1. JARファイルが見つからない
```bash
Error: Could not find or load main class com.edamame.security.NginxLogToMysql
```
**解決方法**: ビルドが正常に完了しているか確認してください。
```bash
./gradlew build
ls build/libs/
```

#### 2. DB接続エラー
```
[ERROR] DB接続試行 #1 失敗: Communications link failure
```
**解決方法**: 
- MySQL接続情報が正しく設定されているか確認
- `setup_secure_config.bat/sh` を再実行してDB設定を更新
- ファイアウォール設定を確認

#### 3. ModSecurityアラートが記録されない
**解決方法**:
- NGINXログにModSecurityの出力が含まれているか確認
- ログファイルのパーミッションを確認
- `modsec_alerts` テーブルの存在を確認

#### 4. Docker権限エラー
```
Permission denied
```
**解決方法**: UID/GIDの設定を確認し、適切な権限を設定してください。

## 📝 ライセンス
このプロジェクトは [ライセンス名] の下で公開されています。詳細は [LICENSE](./LICENSE) ファイルを参照してください。

## 🤝 コントリビューション
コントリビューションを歓迎します！以下の手順でお願いします：

1. このリポジトリをフォーク
2. フィーチャーブランチを作成 (`git checkout -b feature/amazing-feature`)
3. 変更をコミット (`git commit -m 'Add some amazing feature'`)
4. ブランチにプッシュ (`git push origin feature/amazing-feature`)
5. Pull Requestを作成

### 開発ガイドライン
- Java 21の最新機能を活用してください
- すべてのpublicメソッドにJavadocを記述してください
- 変更時は `specification.txt` と `CHANGELOG.md` も更新してください
- テストを追加してください

## 📚 関連ドキュメント
- [技術仕様書](./document/specification.txt)
- [変更履歴](./CHANGELOG.md)
- [Copilot開発指示書](.github/copilot-instructions.md)

## 💬 サポート
質問やバグ報告は [GitHub Issues](https://github.com/your-username/edamame-nginx-analyzer/issues) からお願いします。

---

**Edamame NginxLog Security Analyzer** - Powered by Java 21 ☕
