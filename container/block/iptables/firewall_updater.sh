#!/bin/bash

# Edamame NginxLog Security Analyzer - Advanced Firewall Manager
# 専用チェインを使用した高度なiptables管理スクリプト

# 設定変数
ALERTS_DIR="/app/alerts"
BLOCKED_IPS_FILE="$ALERTS_DIR/blocked_ips.txt"
LOG_FILE="/var/log/edamame-firewall.log"
CHAIN_NAME="EDAMAME_BLOCK"
CONFIG_FILE="/etc/edamame/firewall.conf"

# デフォルト設定
DEFAULT_ACTION="REJECT"  # REJECT または DROP
DEFAULT_REJECT_TYPE="icmp-port-unreachable"  # REJECT時の応答タイプ

# 設定ファイルから設定を読み込み
load_config() {
    if [[ -f "$CONFIG_FILE" ]]; then
        source "$CONFIG_FILE"
        log_message "INFO: Configuration loaded from $CONFIG_FILE"
    else
        # デフォルト設定でconfig作成
        mkdir -p "$(dirname "$CONFIG_FILE")"
        cat > "$CONFIG_FILE" << EOF
# Edamame Firewall Configuration
# ブロック方式: REJECT または DROP
BLOCK_ACTION="$DEFAULT_ACTION"

# REJECT時の応答タイプ
REJECT_TYPE="$DEFAULT_REJECT_TYPE"

# ログ出力レベル: DEBUG, INFO, WARN, ERROR
LOG_LEVEL="INFO"

# 除外IP（ホワイトリスト）- スペース区切り
WHITELIST_IPS="127.0.0.1 ::1"

# 自動クリーンアップ設定（日数）
AUTO_CLEANUP_DAYS="30"
EOF
        log_message "INFO: Default configuration created at $CONFIG_FILE"
        source "$CONFIG_FILE"
    fi
}

# ログ出力関数
log_message() {
    local level="INFO"
    local message="$1"

    if [[ "$1" =~ ^(DEBUG|INFO|WARN|ERROR):.* ]]; then
        level="${1%%:*}"
        message="${1#*: }"
    fi

    echo "[$(date '+%Y-%m-%d %H:%M:%S')] [$level] $message" | tee -a "$LOG_FILE"
}

# IPアドレスのバリデーション（IPv4/IPv6対応）
validate_ip() {
    local ip="$1"

    # IPv4チェック
    if [[ $ip =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
        IFS='.' read -ra OCTETS <<< "$ip"
        for octet in "${OCTETS[@]}"; do
            if ((octet > 255)); then
                return 1
            fi
        done
        return 0
    fi

    # IPv6チェック（簡易版）
    if [[ $ip =~ ^[0-9a-fA-F:]+$ ]] && [[ $ip == *":"* ]]; then
        return 0
    fi

    return 1
}

# ホワイトリストチェック
is_whitelisted() {
    local ip="$1"
    for white_ip in $WHITELIST_IPS; do
        if [[ "$ip" == "$white_ip" ]]; then
            return 0
        fi
    done
    return 1
}

# 専用チェインの作成
create_edamame_chain() {
    log_message "INFO: Creating Edamame firewall chain..."

    # IPv4用チェイン
    if ! iptables -L "$CHAIN_NAME" >/dev/null 2>&1; then
        iptables -N "$CHAIN_NAME"
        log_message "INFO: Created IPv4 chain: $CHAIN_NAME"
    else
        log_message "INFO: IPv4 chain already exists: $CHAIN_NAME"
    fi

    # IPv6用チェイン
    if command -v ip6tables >/dev/null && ! ip6tables -L "$CHAIN_NAME" >/dev/null 2>&1; then
        ip6tables -N "$CHAIN_NAME"
        log_message "INFO: Created IPv6 chain: $CHAIN_NAME"
    fi

    # INPUTチェインの先頭に専用チェインへのジャンプルールを追加
    if ! iptables -C INPUT -j "$CHAIN_NAME" 2>/dev/null; then
        iptables -I INPUT 1 -j "$CHAIN_NAME"
        log_message "INFO: Added jump rule to INPUT chain (IPv4)"
    fi

    if command -v ip6tables >/dev/null && ! ip6tables -C INPUT -j "$CHAIN_NAME" 2>/dev/null; then
        ip6tables -I INPUT 1 -j "$CHAIN_NAME"
        log_message "INFO: Added jump rule to INPUT chain (IPv6)"
    fi

    # チェインの最後にRETURNルールを追加（元のチェインに戻る）
    if ! iptables -C "$CHAIN_NAME" -j RETURN 2>/dev/null; then
        iptables -A "$CHAIN_NAME" -j RETURN
        log_message "INFO: Added RETURN rule to chain (IPv4)"
    fi

    if command -v ip6tables >/dev/null && ! ip6tables -C "$CHAIN_NAME" -j RETURN 2>/dev/null; then
        ip6tables -A "$CHAIN_NAME" -j RETURN
        log_message "INFO: Added RETURN rule to chain (IPv6)"
    fi
}

# IPアドレスのブロック
block_ip() {
    local ip="$1"
    local reason="${2:-Security Alert}"

    # IPアドレスのバリデーション
    if ! validate_ip "$ip"; then
        log_message "ERROR: Invalid IP address format: $ip"
        return 1
    fi

    # ホワイトリストチェック
    if is_whitelisted "$ip"; then
        log_message "WARN: IP $ip is whitelisted, skipping block"
        return 0
    fi

    # IPv4/IPv6判定
    local iptables_cmd="iptables"
    if [[ $ip == *":"* ]]; then
        iptables_cmd="ip6tables"
        if ! command -v ip6tables >/dev/null; then
            log_message "ERROR: ip6tables not available for IPv6 address: $ip"
            return 1
        fi
    fi

    # 既存ルールの確認
    if $iptables_cmd -C "$CHAIN_NAME" -s "$ip" -j "$BLOCK_ACTION" 2>/dev/null; then
        log_message "INFO: IP $ip is already blocked"
        return 0
    fi

    # ブロックルールを専用チェインに追加（RETURNルールの前に挿入）
    local rule_count=$($iptables_cmd -L "$CHAIN_NAME" --line-numbers | grep -c "RETURN")
    local insert_position=$((rule_count > 0 ? rule_count : 1))

    if [[ "$BLOCK_ACTION" == "REJECT" ]]; then
        if $iptables_cmd -I "$CHAIN_NAME" "$insert_position" -s "$ip" -j REJECT --reject-with "$REJECT_TYPE"; then
            log_message "INFO: REJECTED IP $ip ($reason) with $REJECT_TYPE"
        else
            log_message "ERROR: Failed to block IP $ip"
            return 1
        fi
    else
        if $iptables_cmd -I "$CHAIN_NAME" "$insert_position" -s "$ip" -j DROP; then
            log_message "INFO: DROPPED IP $ip ($reason)"
        else
            log_message "ERROR: Failed to block IP $ip"
            return 1
        fi
    fi

    # 設定の永続化
    save_iptables_rules

    return 0
}

# IPアドレスのブロック解除
unblock_ip() {
    local ip="$1"

    if ! validate_ip "$ip"; then
        log_message "ERROR: Invalid IP address format: $ip"
        return 1
    fi

    local iptables_cmd="iptables"
    if [[ $ip == *":"* ]]; then
        iptables_cmd="ip6tables"
    fi

    # ブロックルールを削除
    if $iptables_cmd -D "$CHAIN_NAME" -s "$ip" -j "$BLOCK_ACTION" 2>/dev/null; then
        log_message "INFO: Unblocked IP $ip"
        save_iptables_rules
        return 0
    else
        log_message "WARN: No blocking rule found for IP $ip"
        return 1
    fi
}

# iptables設定の永続化
save_iptables_rules() {
    if command -v iptables-save >/dev/null; then
        # systemd系
        if [[ -d /etc/iptables ]]; then
            iptables-save > /etc/iptables/rules.v4 2>/dev/null && \
                log_message "DEBUG: IPv4 rules saved to /etc/iptables/rules.v4"
        # RHEL/CentOS系
        elif [[ -d /etc/sysconfig ]]; then
            iptables-save > /etc/sysconfig/iptables 2>/dev/null && \
                log_message "DEBUG: IPv4 rules saved to /etc/sysconfig/iptables"
        fi

        # IPv6
        if command -v ip6tables-save >/dev/null; then
            if [[ -d /etc/iptables ]]; then
                ip6tables-save > /etc/iptables/rules.v6 2>/dev/null && \
                    log_message "DEBUG: IPv6 rules saved to /etc/iptables/rules.v6"
            elif [[ -d /etc/sysconfig ]]; then
                ip6tables-save > /etc/sysconfig/ip6tables 2>/dev/null && \
                    log_message "DEBUG: IPv6 rules saved to /etc/sysconfig/ip6tables"
            fi
        fi
    fi
}

# 統計情報の表示
show_statistics() {
    log_message "INFO: === Edamame Firewall Statistics ==="

    local ipv4_count=$(iptables -L "$CHAIN_NAME" -n | grep -c "^$BLOCK_ACTION")
    log_message "INFO: IPv4 blocked IPs: $ipv4_count"

    if command -v ip6tables >/dev/null; then
        local ipv6_count=$(ip6tables -L "$CHAIN_NAME" -n | grep -c "^$BLOCK_ACTION")
        log_message "INFO: IPv6 blocked IPs: $ipv6_count"
    fi

    log_message "INFO: Block action: $BLOCK_ACTION"
    log_message "INFO: Configuration: $CONFIG_FILE"
    log_message "INFO: =================================="
}

# 古いルールのクリーンアップ
cleanup_old_rules() {
    if [[ -n "$AUTO_CLEANUP_DAYS" ]] && ((AUTO_CLEANUP_DAYS > 0)); then
        log_message "INFO: Cleaning up rules older than $AUTO_CLEANUP_DAYS days"
        # 実装は環境に依存するため、ログファイルベースのクリーンアップとする
        find "$LOG_FILE" -mtime +"$AUTO_CLEANUP_DAYS" -delete 2>/dev/null || true
    fi
}

# メイン処理
main() {
    log_message "INFO: Edamame Advanced Firewall Manager started"

    # 権限チェック
    if [[ $EUID -ne 0 ]]; then
        log_message "ERROR: This script must be run as root for iptables access"
        exit 1
    fi

    # 設定読み込み
    load_config

    # アラートディレクトリの確認
    if [[ ! -d "$ALERTS_DIR" ]]; then
        log_message "ERROR: Alerts directory not found: $ALERTS_DIR"
        exit 1
    fi

    # inotifyツールの確認
    if ! command -v inotifywait >/dev/null; then
        log_message "ERROR: inotifywait not found. Please install inotify-tools"
        exit 1
    fi

    # 専用チェインの作成
    create_edamame_chain

    # 統計情報表示
    show_statistics

    log_message "INFO: Monitoring $BLOCKED_IPS_FILE for changes..."
    log_message "INFO: Block action: $BLOCK_ACTION"

    # ファイル監視ループ
    while true; do
        # blocked_ips.txtの変更を監視
        if inotifywait -e modify,create "$BLOCKED_IPS_FILE" 2>/dev/null; then
            log_message "INFO: Detected changes in blocked_ips.txt"

            # 新しいIP���ドレスを処理
            if [[ -f "$BLOCKED_IPS_FILE" ]]; then
                while IFS= read -r ip; do
                    # コメント行と空行をスキップ
                    if [[ -n "$ip" && ! "$ip" =~ ^[[:space:]]*# ]]; then
                        block_ip "$ip" "Edamame Security Alert"
                    fi
                done < "$BLOCKED_IPS_FILE"
            fi
        fi

        # 定期クリーンアップ
        cleanup_old_rules

        # 短時間の待機
        sleep 1
    done
}

# コマンドライン引数の処理
case "${1:-}" in
    "start"|"")
        main "$@"
        ;;
    "stop")
        log_message "INFO: Stopping Edamame Firewall Manager"
        # 専用チェインの削除（オプション）
        read -p "Remove Edamame firewall chain? [y/N]: " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            iptables -D INPUT -j "$CHAIN_NAME" 2>/dev/null || true
            iptables -F "$CHAIN_NAME" 2>/dev/null || true
            iptables -X "$CHAIN_NAME" 2>/dev/null || true
            if command -v ip6tables >/dev/null; then
                ip6tables -D INPUT -j "$CHAIN_NAME" 2>/dev/null || true
                ip6tables -F "$CHAIN_NAME" 2>/dev/null || true
                ip6tables -X "$CHAIN_NAME" 2>/dev/null || true
            fi
            log_message "INFO: Edamame firewall chain removed"
        fi
        ;;
    "status")
        load_config
        show_statistics
        ;;
    "block")
        if [[ -n "${2:-}" ]]; then
            load_config
            create_edamame_chain
            block_ip "$2" "Manual block"
        else
            echo "Usage: $0 block <IP_ADDRESS>"
            exit 1
        fi
        ;;
    "unblock")
        if [[ -n "${2:-}" ]]; then
            load_config
            unblock_ip "$2"
        else
            echo "Usage: $0 unblock <IP_ADDRESS>"
            exit 1
        fi
        ;;
    "list")
        load_config
        echo "=== Blocked IPs (IPv4) ==="
        iptables -L "$CHAIN_NAME" -n --line-numbers 2>/dev/null | grep -E "^[0-9]" || echo "No rules found"
        if command -v ip6tables >/dev/null; then
            echo "=== Blocked IPs (IPv6) ==="
            ip6tables -L "$CHAIN_NAME" -n --line-numbers 2>/dev/null | grep -E "^[0-9]" || echo "No rules found"
        fi
        ;;
    *)
        echo "Edamame Advanced Firewall Manager"
        echo "Usage: $0 [start|stop|status|block <ip>|unblock <ip>|list]"
        echo ""
        echo "Commands:"
        echo "  start     - Start monitoring (default)"
        echo "  stop      - Stop monitoring and optionally remove chain"
        echo "  status    - Show statistics"
        echo "  block     - Manually block an IP address"
        echo "  unblock   - Manually unblock an IP address"
        echo "  list      - List all blocked IPs"
        echo ""
        echo "Configuration file: $CONFIG_FILE"
        exit 1
        ;;
esac
