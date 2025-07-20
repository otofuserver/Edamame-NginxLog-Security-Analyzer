#!/bin/bash

# Edamame NginxLog Security Analyzer - Firewall Updater Script
# ホスト側でiptablesを更新するためのスクリプト

ALERTS_DIR="/app/alerts"
BLOCKED_IPS_FILE="$ALERTS_DIR/blocked_ips.txt"
EMERGENCY_ALERTS_FILE="$ALERTS_DIR/emergency_alerts.txt"
LOG_FILE="/var/log/edamame-firewall.log"

# ログ出力関数
log_message() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" | tee -a "$LOG_FILE"
}

# iptablesルールの確認
check_existing_rule() {
    local ip="$1"
    iptables -C INPUT -s "$ip" -j DROP 2>/dev/null
    return $?
}

# IPアドレスのバリデーション
validate_ip() {
    local ip="$1"
    if [[ $ip =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
        return 0
    else
        return 1
    fi
}

# IPアドレスをiptablesでブロック
block_ip() {
    local ip="$1"

    # IPアドレスのバリデーション
    if ! validate_ip "$ip"; then
        log_message "ERROR: Invalid IP address format: $ip"
        return 1
    fi

    # 既存ルールの確認
    if check_existing_rule "$ip"; then
        log_message "INFO: IP $ip is already blocked"
        return 0
    fi

    # iptablesルールを追加
    if iptables -A INPUT -s "$ip" -j DROP; then
        log_message "SUCCESS: Blocked IP address $ip"

        # 設定を永続化（system dependent）
        if command -v iptables-save > /dev/null; then
            iptables-save > /etc/iptables/rules.v4 2>/dev/null || \
            iptables-save > /etc/sysconfig/iptables 2>/dev/null || \
            log_message "WARN: Could not save iptables rules persistently"
        fi

        return 0
    else
        log_message "ERROR: Failed to block IP address $ip"
        return 1
    fi
}

# 緊急アラートの処理
process_emergency_alerts() {
    if [[ -f "$EMERGENCY_ALERTS_FILE" ]]; then
        while IFS= read -r alert_line; do
            if [[ -n "$alert_line" ]]; then
                log_message "EMERGENCY ALERT: $alert_line"

                # Webhook通知やメール送信などの緊急処理
                # curl -X POST "https://hooks.slack.com/..." -d "payload={\"text\":\"$alert_line\"}" 2>/dev/null || true

                # SMS通知やその他の緊急連絡手段も実装可能
            fi
        done < "$EMERGENCY_ALERTS_FILE"

        # 処理済みファイルをバックアップして削除
        if [[ -s "$EMERGENCY_ALERTS_FILE" ]]; then
            mv "$EMERGENCY_ALERTS_FILE" "$EMERGENCY_ALERTS_FILE.$(date +%s).processed"
        fi
    fi
}

# メイン処理
main() {
    log_message "INFO: Edamame Firewall Updater started"

    # 権限チェック
    if [[ $EUID -ne 0 ]]; then
        log_message "ERROR: This script must be run as root for iptables access"
        exit 1
    fi

    # アラートディレクトリの確認
    if [[ ! -d "$ALERTS_DIR" ]]; then
        log_message "ERROR: Alerts directory not found: $ALERTS_DIR"
        exit 1
    fi

    # inotifyツールの確認
    if ! command -v inotifywait > /dev/null; then
        log_message "ERROR: inotifywait not found. Please install inotify-tools"
        exit 1
    fi

    log_message "INFO: Monitoring $BLOCKED_IPS_FILE for changes..."

    # ファイル監視ループ
    while true; do
        # blocked_ips.txtの変更を監視
        if inotifywait -e modify,create "$BLOCKED_IPS_FILE" 2>/dev/null; then
            log_message "INFO: Detected changes in blocked_ips.txt"

            # 新しいIPアドレスを処理
            if [[ -f "$BLOCKED_IPS_FILE" ]]; then
                while IFS= read -r ip; do
                    if [[ -n "$ip" && ! "$ip" =~ ^[[:space:]]*# ]]; then
                        block_ip "$ip"
                    fi
                done < "$BLOCKED_IPS_FILE"
            fi
        fi

        # 緊急アラートの処理
        process_emergency_alerts

        # 短時間の待機
        sleep 1
    done
}

# スクリプト実行
main "$@"
