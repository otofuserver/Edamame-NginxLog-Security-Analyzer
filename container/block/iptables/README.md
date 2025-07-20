# Edamame Firewall Manager

高度なiptables管理システムによる自動IP ブロック機能

## 🔒 機能概要

- **専用チェイン管理**: `EDAMAME_BLOCK`専用チェインを自動作成
- **IPv4/IPv6対応**: 両方のプロトコルに対応
- **柔軟なブロック方式**: REJECT（推奨）またはDROPを選択可能
- **自動永続化**: iptables設定の自動保存
- **ホワイトリスト機能**: 重要IPの除外設定
- **リアルタイム監視**: blocked_ips.txtファイルの変更を即座に反映

## 📁 ファイル構成

```
container/
├── block/
│   └── iptables/
│       └── firewall_updater.sh      # メインスクリプト
├── docker-compose.yml               # Docker設定（alerts ボリューム追加済み）
└── alerts/                          # アラートファイル出力先
    └── blocked_ips.txt              # ブロック対象IPリスト
```

## 🚀 使用方法

### 1. ホスト側での実行

```bash
# 基本的な監視開始
sudo ./container/block/iptables/firewall_updater.sh start

# バックグラウンドで実行
sudo nohup ./container/block/iptables/firewall_updater.sh start > /var/log/edamame-firewall.log 2>&1 &
```

### 2. コマンドライン操作

```bash
# 統計情報表示
sudo ./firewall_updater.sh status

# 手動IPブロック
sudo ./firewall_updater.sh block 192.168.1.100

# IPブロック解除
sudo ./firewall_updater.sh unblock 192.168.1.100

# ブロック済みIP一覧
sudo ./firewall_updater.sh list

# 監視停止
sudo ./firewall_updater.sh stop
```

## ⚙️ 設定ファイル

設定ファイル: `/etc/edamame/firewall.conf`

```bash
# ブロック方式: REJECT または DROP
BLOCK_ACTION="REJECT"

# REJECT時の応答タイプ
REJECT_TYPE="icmp-port-unreachable"

# ログ出力レベル
LOG_LEVEL="INFO"

# ホワイトリストIP（スペース区切り）
WHITELIST_IPS="127.0.0.1 ::1 192.168.1.1"

# 自動クリーンアップ（日数）
AUTO_CLEANUP_DAYS="30"
```

## 🛡️ iptables構造

### 作成されるチェイン構造

```
INPUT チェイン
├── 1. -j EDAMAME_BLOCK     ← 最優先で専用チェインにジャンプ
├── 2. 既存のルール...
└── n. 既存のルール...

EDAMAME_BLOCK チェイン
├── 1. -s 攻撃IP1 -j REJECT
├── 2. -s 攻撃IP2 -j REJECT
├── ...
└── n. -j RETURN           ← 元のINPUTチェインに戻る
```

### ブロック方式の違い

| 方式 | 動作 | 利点 | 欠点 |
|------|------|------|------|
| **REJECT** | ICMP応答を返して拒否 | 正当な接続の高速復旧 | 攻撃者にサービス存在を通知 |
| **DROP** | パケットを破棄（無応答） | 完全なステルス動作 | タイムアウト待機でリソース消費 |

## 🐳 Docker環境での設定

### docker-compose.yml設定

```yaml
services:
  nginxlog:
    volumes:
      - ./alerts:/app/alerts  # アラートファイル共有
    environment:
      - ALERTS_OUTPUT_PATH=/app/alerts
```

### ホスト側監視スクリプトの実行

```bash
# Dockerコンテナ起動
cd container && docker-compose up -d

# ホスト側でファイアウォール監視開始
sudo ./block/iptables/firewall_updater.sh start
```

## 📊 ログとモニタリング

### ログファイル
- **アプリケーションログ**: コンテナ内のJavaログ出力
- **ファイアウォールログ**: `/var/log/edamame-firewall.log`
- **アラートファイル**: `./alerts/blocked_ips.txt`

### 監視例
```bash
# リアルタイムログ監視
tail -f /var/log/edamame-firewall.log

# アラートファイル監視
watch -n 1 'cat ./alerts/blocked_ips.txt | wc -l'

# iptablesルール確認
sudo iptables -L EDAMAME_BLOCK -n --line-numbers
```

## 🔧 トラブルシューティング

### よくある問題

1. **権限エラー**
   ```bash
   # 解決方法: root権限で実行
   sudo ./firewall_updater.sh start
   ```

2. **inotify-toolsが見つからない**
   ```bash
   # Ubuntu/Debian
   sudo apt-get install inotify-tools
   
   # RHEL/CentOS
   sudo yum install inotify-tools
   ```

3. **設定の永続化失敗**
   ```bash
   # 手動保存
   sudo iptables-save > /etc/iptables/rules.v4
   ```

### デバッグモード
```bash
# 詳細ログでの実行
LOG_LEVEL="DEBUG" sudo ./firewall_updater.sh start
```

## 🚨 セキュリティ注意事項

1. **ホワイトリスト設定**: 管理IPを必ずホワイトリストに追加
2. **設定バックアップ**: 定期的にiptables設定をバックアップ
3. **監視ログ**: 不正なブロック状況を定期確認
4. **緊急時対応**: SSH接続できなくなった場合の復旧手順を準備

## 📝 変更履歴

- v1.0.0: 基本的なIPブロック機能
- v1.0.0: 専用チェイン対応、IPv6対応、設定ファイル機能追加
