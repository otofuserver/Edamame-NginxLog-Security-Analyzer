# Edamame Agent README
**分散ログ収集・セキュリティエージェント**

## 概要
Edamame Agentは、Nginxログの自動収集・転送、IPブロック管理、サーバー自動登録を行う分散セキュリティエージェントです。

## 動作環境
- **Java**: 11以上 (推奨: 21)
- **OS**: Linux (CentOS/Ubuntu/RHEL) / Windows Server 2019+
- **権限**: Linux root権限 / Windows 管理者権限
- **ネットワーク**: 枝豆コンテナへのHTTP/HTTPS接続

## クイックスタート

### Linux環境
```bash
# 1. ファイル配置
sudo chmod +x install-linux.sh
sudo ./install-linux.sh

# 2. 設定編集
sudo nano /etc/edamame-agent/agent-config.json

# 3. サービス開始
sudo systemctl enable edamame-agent
sudo systemctl start edamame-agent

# 4. 状態確認
sudo systemctl status edamame-agent
sudo journalctl -u edamame-agent -f
```

### Windows環境
```cmd
REM 1. 管理者としてコマンドプロンプトを開く
REM 2. インストール実行
install-windows.bat

REM 3. 設定編集
notepad "C:\ProgramData\EdamameAgent\agent-config.json"

REM 4. サービス開始
sc start EdamameAgent

REM 5. 状態確認
sc query EdamameAgent
```

## 設定ファイル

### 必須設定項目
```json
{
  "server": {
    "name": "your-server-name",
    "ip": "サーバーIP",
    "port": 80
  },
  "edamame": {
    "host": "枝豆コンテナのホスト",
    "port": 8080,
    "apiKey": "API認証キー"
  },
  "logging": {
    "nginxLogPaths": ["/var/log/nginx/access.log"]
  }
}
```

### 主要設定項目
- **logging.collectionInterval**: ログ収集間隔（秒、デフォルト: 10）
- **iptables.enabled**: iptables機能有効化（デフォルト: true）
- **heartbeat.interval**: ハートビート送信間隔（秒、デフォルト: 60）

## 運用コマンド

### Linux
```bash
# サービス制御
sudo systemctl start edamame-agent
sudo systemctl stop edamame-agent
sudo systemctl restart edamame-agent

# ログ確認
sudo journalctl -u edamame-agent
sudo journalctl -u edamame-agent -f --since "1 hour ago"

# 設定リロード
sudo systemctl reload edamame-agent
```

### Windows
```cmd
# サービス制御
sc start EdamameAgent
sc stop EdamameAgent
sc query EdamameAgent

# ログ確認（イベントビューアー）
eventvwr.msc

# 手動実行（デバッグ用）
java -jar "C:\Program Files\EdamameAgent\EdamameAgent-1.0.1.jar" "C:\ProgramData\EdamameAgent\agent-config.json"
```

## トラブルシューティング

### よくある問題
1. **Java未インストール**
   - Java 11以上をインストール
   - 環境変数PATH設定確認

2. **権限エラー**
   - Linux: sudo権限確認、iptables実行権限確認
   - Windows: 管理者権限で実行

3. **ネットワーク接続エラー**
   - 枝豆コンテナへの接続確認
   - ファイアウォール設定確認
   - APIキー設定確認

4. **ログファイルアクセスエラー**
   - Nginxログファイルの読み取り権限確認
   - ログファイルパス設定確認

### ログレベル変更
```json
{
  "advanced": {
    "logLevel": "DEBUG",
    "enableDebug": true
  }
}
```

## アンインストール

### Linux
```bash
sudo systemctl stop edamame-agent
sudo systemctl disable edamame-agent
sudo rm /etc/systemd/system/edamame-agent.service
sudo systemctl daemon-reload
sudo rm -rf /opt/edamame-agent /etc/edamame-agent
sudo userdel edamame-agent
```

### Windows
```cmd
sc stop EdamameAgent
sc delete EdamameAgent
rmdir /s "C:\Program Files\EdamameAgent"
rmdir /s "C:\ProgramData\EdamameAgent"
```

## セキュリティ注意事項
- 設定ファイルは適切な権限で保護してください（Linux: 600権限）
- APIキーは安全に管理してください
- 定期的にエージェントのバージョンアップを実施してください

## サポート
- 詳細仕様: `document/agent_system_spec.md`
- 問題報告: GitHub Issues
- バージョン: 1.0.1
