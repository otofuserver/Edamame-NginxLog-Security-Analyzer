#!/bin/bash
# Edamame Agent Installation Script for Linux
# エージェント自動インストールスクリプト

set -e

AGENT_VERSION="1.0.1"
INSTALL_DIR="/opt/edamame-agent"
CONFIG_DIR="/etc/edamame-agent"
SERVICE_NAME="edamame-agent"

echo "=== Edamame Agent Installer v${AGENT_VERSION} ==="

# 管理者権限チェック
if [[ $EUID -ne 0 ]]; then
   echo "このスクリプトは管理者権限で実行してください (sudo)"
   exit 1
fi

# Java環境チェック
if ! command -v java &> /dev/null; then
    echo "Javaがインストールされていません。Java 11以上をインストールしてください。"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | grep -o '"[0-9][0-9]*' | sed 's/"//g')
if [[ $JAVA_VERSION -lt 11 ]]; then
    echo "Java 11以上が必要です。現在のバージョン: $JAVA_VERSION"
    exit 1
fi

echo "✓ Java $JAVA_VERSION が確認されました"

# ディレクトリ作成
echo "インストールディレクトリを作成中..."
mkdir -p $INSTALL_DIR
mkdir -p $CONFIG_DIR

# エージェント用ユーザー作成
if ! id "edamame-agent" &>/dev/null; then
    echo "エージェント用ユーザーを作成中..."
    useradd -r -s /bin/false -d $INSTALL_DIR edamame-agent
fi

# JARファイル配置
if [[ -f "EdamameAgent-${AGENT_VERSION}.jar" ]]; then
    echo "エージェントJARファイルをコピー中..."
    cp "EdamameAgent-${AGENT_VERSION}.jar" "$INSTALL_DIR/"
    chmod +x "$INSTALL_DIR/EdamameAgent-${AGENT_VERSION}.jar"
else
    echo "エラー: EdamameAgent-${AGENT_VERSION}.jar が見つかりません"
    exit 1
fi

# 設定ファイル配置
if [[ -f "config/agent-config.json" ]]; then
    echo "設定ファイルをコピー中..."
    cp "config/agent-config.json" "$CONFIG_DIR/"
    chmod 600 "$CONFIG_DIR/agent-config.json"
else
    echo "警告: 設定ファイルが見つかりません。デフォルト設定を使用します"
fi

# 権限設定
chown -R edamame-agent:edamame-agent $INSTALL_DIR $CONFIG_DIR

# systemdサービス作成
echo "systemdサービスを作成中..."
cat > /etc/systemd/system/${SERVICE_NAME}.service << EOF
[Unit]
Description=Edamame Security Agent
Documentation=https://github.com/edamame-security/agent
After=network.target

[Service]
Type=simple
User=edamame-agent
Group=edamame-agent
ExecStart=/usr/bin/java -jar ${INSTALL_DIR}/EdamameAgent-${AGENT_VERSION}.jar ${CONFIG_DIR}/agent-config.json
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

# セキュリティ設定
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=${CONFIG_DIR}

[Install]
WantedBy=multi-user.target
EOF

# systemd設定リロード
systemctl daemon-reload

echo ""
echo "=== インストール完了 ==="
echo "インストール場所: $INSTALL_DIR"
echo "設定ファイル: $CONFIG_DIR/agent-config.json"
echo ""
echo "設定ファイルを編集してから以下のコマンドでサービスを開始してください:"
echo "  sudo systemctl enable $SERVICE_NAME"
echo "  sudo systemctl start $SERVICE_NAME"
echo ""
echo "サービス状態確認:"
echo "  sudo systemctl status $SERVICE_NAME"
echo ""
echo "ログ確認:"
echo "  sudo journalctl -u $SERVICE_NAME -f"
