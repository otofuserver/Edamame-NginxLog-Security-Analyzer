# Edamame NginxLog Security Analyzer

> **このリポジトリはEdamame NginxLog Security Analyzerのバックエンド実装です。**
> フロントエンドは公式のもの（例：edamame-frontend）を利用しても、独自開発のものを利用しても構いません（ただし動作保証はありません）。

## 概要
Edamame NginxLog Security Analyzerは、NGINXのアクセスログをリアルタイムで監視し、MySQLデータベースに記録・分析するセキュリティ監査ツールです。ModSecurityによるブロックや攻撃パターンの自動識別、ホワイトリスト管理など、Webサーバのセキュリティ運用を強力にサポートします。

## 主な機能
- NGINXアクセスログのリアルタイム監視・DB記録
- ModSecurityブロック検知と詳細アラート記録
- 攻撃パターン（SQLi, XSS, LFI等）の自動識別
- URLごとのホワイトリスト管理
- Docker対応・セキュアなDB接続情報管理

## ディレクトリ構成
```
.
├── nginx_log_to_mysql.py         # メイン監視・記録スクリプト
├── setup_secure_config.py        # DB接続情報の暗号化設定スクリプト
├── attack_patterns.json          # 攻撃パターン定義ファイル
├── docker-compose.yml            # Docker構成ファイル
├── Dockerfile                    # Dockerビルド用ファイル
└── document/
    └── specification             # 仕様書

```

## セットアップ方法

### 事前準備（Dockerユーザー権限の調整）

1. **ホストサーバーでsyslogユーザーのUID/GIDを確認**
   ```bash
   id syslog
   ```
   例: `uid=101(syslog) gid=103(syslog) ...`

2. **Dockerfileのユーザー作成部分を、上記で確認したUID/GIDに書き換える**
   - 例: `useradd -u 101 -g 103 ...` のように修正

3. **（推奨）ホストサーバー上のプロジェクトフォルダの所有者をsyslogユーザーに変更**
   ```bash
   sudo chown -R syslog:syslog /path/to/Edamame NginxLog Security Analyzer
   ```

---

### 1. DB接続情報の暗号化設定
```bash
python3 setup_secure_config.py
```
このコマンドで `secret.key` と `db_config.enc` が生成されます。

### 2. Dockerイメージのビルドと起動
```bash
docker compose up -d --build
```

### 3. ログ監視の開始
Dockerコンテナ起動後、自動的にログ監視・DB記録が始まります。

### 4. ログ・状態の確認
```bash
docker logs -f nginxlog-LO
```

## 仕様・詳細
- 詳細な仕様やDBスキーマは [`document/specification`](document/specification.txt) を参照してください。
- 攻撃パターンのバージョン管理や追加は [`attack_patterns.json`](attack_patterns.json) を編集してください。


---
