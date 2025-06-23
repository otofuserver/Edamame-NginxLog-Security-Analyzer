#!/usr/bin/env python3
# file: nginx_log_to_mysql.py

# 必要なモジュールをインポート
import re
import time
import argparse
from datetime import datetime
import mysql.connector
from mysql.connector import Error
from cryptography.fernet import Fernet
import json
from urllib.parse import unquote
import sys
import os

# アプリケーション情報
APP_NAME = "Edamame NginxLog Security Analyzer"
APP_VERSION = "v1.0.0"
APP_AUTHOR = "Developed by Code Copilot"

# ログファイルのパス
LOG_PATH = "/var/log/nginx/nginx.log"
# 暗号化された設定ファイルと鍵のパス
SECURE_CONFIG_PATH = "/run/secrets/db_config_enc"
KEY_PATH = "/run/secrets/db_secret_key"

# ホワイトリストモードと登録IPの初期値
WHITELIST_MODE = False
WHITELIST_IP = ""

# Nginxログの正規表現パターン
LOG_PATTERN = re.compile(
    r'(?P<ip>\d+\.\d+\.\d+\.\d+) - - \[(?P<time>[^\]]+)\] "(?P<method>GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH) (?P<url>[^ ]+) HTTP.*?" (?P<status>\d{3})'
)

# DB再接続の最大試行回数と待機時間
MAX_RETRIES = 5
RETRY_DELAY = 3

# グローバルDB接続セッション
DB_SESSION = None

# DB接続情報を復号化して取得
def load_db_config():
    with open(KEY_PATH, 'rb') as key_file:
        key = key_file.read()
    fernet = Fernet(key)
    with open(SECURE_CONFIG_PATH, 'rb') as enc_file:
        decrypted = fernet.decrypt(enc_file.read())
    return json.loads(decrypted)

# DBへ接続（失敗時は最大N回リトライ）
def db_connect():
    global DB_SESSION
    if DB_SESSION is not None and DB_SESSION.is_connected():
        return DB_SESSION

    print("[INFO] Attempting to connect to the database...")
    config = load_db_config()
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            conn = mysql.connector.connect(**config)
            if conn.is_connected():
                DB_SESSION = conn
                if attempt > 1:
                    print(f"[RECOVERED] DB connection recovered on retry #{attempt}.")
                    print(f"[INFO] DB connection successful on retry #{attempt}.")
                else:
                    print("[INFO] Database connection established successfully.")
                return DB_SESSION
        except Error as e:
            print(f"[DB ERROR] Connection attempt {attempt} failed: {e}")
            if attempt == MAX_RETRIES:
                print("[DB ERROR] Max retries exceeded. Exiting.")
                sys.exit(1)
            time.sleep(RETRY_DELAY)

# DBの初期テーブル構造を作成（なければ）
# ModSecurityアラート詳細保存用テーブルを作成（ブロック理由など）
MODSEC_ALERT_TABLE = "modsec_alerts"

def init_db():
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("UPDATE settings SET backend_version = %s WHERE id = 1", (APP_VERSION,))
        print(f"[{datetime.now()}] [INFO] backend_version updated to {APP_VERSION} in settings table.")
        conn.commit()
    except Error as e:
        print(f"[{datetime.now()}] [DB ERROR] Failed to update backend_version: {e}")
    success = False
    try:
        conn = db_connect()
        cursor = conn.cursor()
        # URL登録テーブル（初回のみ一意登録）
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS url_registry (
                id INT AUTO_INCREMENT PRIMARY KEY,
                method VARCHAR(10),
                full_url TEXT,
                created_at DATETIME,
                updated_at DATETIME,
                is_whitelisted BOOLEAN,
                description TEXT,
                attack_type VARCHAR(255)
            )
        """)
        # 全アクセス記録用テーブル
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS access_log (
                id INT AUTO_INCREMENT PRIMARY KEY,
                method VARCHAR(10),
                full_url TEXT,
                status_code INT,
                ip_address VARCHAR(45),
                access_time DATETIME,
                blocked_by_modsec BOOLEAN
            )
        """)
        # ホワイトリスト設定管理テーブル
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS settings (
                id INT PRIMARY KEY,
                whitelist_mode BOOLEAN,
                whitelist_ip VARCHAR(45),
                backend_version VARCHAR(50),
                frontend_version VARCHAR(50),
                frontend_last_login DATETIME,
                frontend_last_ip VARCHAR(45)
            )
        """)

        # ModSecurityの詳細ログテーブル
        cursor.execute(f"""
            CREATE TABLE IF NOT EXISTS {MODSEC_ALERT_TABLE} (
                id INT AUTO_INCREMENT PRIMARY KEY,
                access_log_id INT,
                rule_id VARCHAR(20),
                msg TEXT,
                data TEXT,
                severity VARCHAR(10),
                UNIQUE KEY unique_log_rule (access_log_id, rule_id),
                FOREIGN KEY (access_log_id) REFERENCES access_log(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
        # 初期設定がなければ作成
        cursor.execute("SELECT COUNT(*) FROM settings")
        if cursor.fetchone()[0] == 0:
            cursor.execute("""
                INSERT INTO settings (
                    id, whitelist_mode, whitelist_ip,
                    backend_version, frontend_version,
                    frontend_last_login, frontend_last_ip
                ) VALUES (%s, %s, %s, %s, %s, %s, %s)
            """, (1, False, '', APP_VERSION, '', None, ''))
        conn.commit()
        success = True
        # カラム追加が必要なテーブルと定義を順にチェック
        required_columns = {
            "url_registry": [
                ("method", "VARCHAR(10)"),
                ("full_url", "TEXT"),
                ("created_at", "DATETIME"),
                ("updated_at", "DATETIME AFTER created_at"),
                ("is_whitelisted", "BOOLEAN"),
                ("description", "TEXT"),
                ("attack_type", "VARCHAR(255) AFTER description")
            ],
            "settings": [
                ("whitelist_mode", "BOOLEAN"),
                ("whitelist_ip", "VARCHAR(45)"),
                ("backend_version", "VARCHAR(50)"),
                ("frontend_version", "VARCHAR(50)"),
                ("frontend_last_login", "DATETIME"),
                ("frontend_last_ip", "VARCHAR(45)")
            ],
            "access_log": [
                ("method", "VARCHAR(10)"),
                ("full_url", "TEXT"),
                ("status_code", "INT"),
                ("ip_address", "VARCHAR(45)"),
                ("access_time", "DATETIME"),
                ("blocked_by_modsec", "BOOLEAN")
            ],
            "modsec_alerts": [
                ("access_log_id", "INT"),
                ("rule_id", "VARCHAR(20)"),
                ("msg", "TEXT"),
                ("data", "TEXT"),
                ("severity", "VARCHAR(10)")
            ]
        }
        for table, columns in required_columns.items():
            for column_name, ddl in columns:
                cursor.execute(f"SHOW COLUMNS FROM {table} LIKE %s", (column_name,))
                if cursor.fetchone() is None:
                    print(
                        f"[{datetime.now()}] [INFO] Adding missing column '{column_name}' to {table} using DDL: ALTER TABLE {table} ADD COLUMN {column_name} {ddl}"
                    )
                    cursor.execute(f"ALTER TABLE {table} ADD COLUMN {column_name} {ddl}")
                    conn.commit()
    except Error as e:
        print(f"[{datetime.now()}] [DB ERROR] init_db failed: {e}")
        return False
    return success

# DBから最新のホワイトリスト設定を取得
def fetch_settings():
    global WHITELIST_MODE, WHITELIST_IP
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("SELECT whitelist_mode, whitelist_ip FROM settings WHERE id = 1")
        row = cursor.fetchone()
        if row:
            WHITELIST_MODE = bool(row[0])
            WHITELIST_IP = row[1]
    except Error as e:
        print(f"[DB ERROR] fetch_settings failed: {e}")

# 未登録のURLをurl_registryに登録（ホワイトリストIPはwhitelistとして登録）
def add_registry_entry(method, url, ip, access_time):
    """
    url_registryへ新規URLを登録する。ホワイトリストIPの場合はis_whitelistedも設定。
    created_at/updated_atにはアクセス時刻（ログから取得）を使用。
    """
    attack_type = detect_attack_type(url)
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("SELECT id, is_whitelisted FROM url_registry WHERE full_url=%s", (url,))
        row = cursor.fetchone()
        is_whitelisted = WHITELIST_MODE and ip == WHITELIST_IP
        if row is None:
            cursor.execute(
                """
                INSERT INTO url_registry (method, full_url, created_at, is_whitelisted, description, updated_at, attack_type)
                VALUES (%s, %s, %s, %s, NULL, %s, %s)
                """,
                (method, url, access_time, is_whitelisted, access_time, attack_type),
            )
            conn.commit()
        elif is_whitelisted and not row[1]:
            cursor.execute(
                "UPDATE url_registry SET is_whitelisted = TRUE, updated_at = %s WHERE full_url = %s",
                (access_time, url),
            )
            conn.commit()
            print(f"[{datetime.now()}] [INFO] Updated whitelist status for URL: {url}")
    except Error as e:
        print(f"[DB ERROR] add_registry_entry failed: {e}")

# 全アクセスをaccess_logに追記
def add_access_log(method, url, status, ip, access_time, blocked=False):
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO access_log (method, full_url, status_code, ip_address, access_time, blocked_by_modsec)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (method, url, int(status), ip, access_time, blocked),
        )
        conn.commit()
    except Error as e:
        print(f"[DB ERROR] add_access_log failed: {e}")

# ログ1行を処理：アクセス保存＋新URL検出
# URLに含まれる攻撃タイプを識別
ATTACK_PATTERNS_PATH = "/run/secrets/attack_patterns.json"

def detect_attack_type(url):
    try:
        url = unquote(url)
        with open(ATTACK_PATTERNS_PATH, "r") as f:
            patterns = json.load(f)
        matches = []
        for attack_name, pattern in patterns.items():
            if re.search(pattern, url, re.IGNORECASE):
                matches.append(attack_name)
        if matches:
            return ",".join(matches)
    except Exception as e:
        print(f"[ERROR] Failed to detect attack type: {e}")
    return None

# ModSecurityによるブロックを判定するキーワード
MODSEC_BLOCK_PATTERN = re.compile(r"ModSecurity: Access denied", re.IGNORECASE)

# ModSecurityルール詳細を抽出しmodsec_alertsに記録
MODSEC_RULE_PATTERN = re.compile(
    r'ModSecurity: Access denied.*?\[id "(?P<id>\d+)"\].*?\[msg "(?P<msg>.*?)"\].*?\[data "(?P<data>.*?)"\].*?\[severity "(?P<severity>\d+)"\]',
    re.IGNORECASE | re.DOTALL,
)

def add_modsec_alert(access_log_id, rule_id, msg, data, severity):
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT IGNORE INTO modsec_alerts (access_log_id, rule_id, msg, data, severity)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (access_log_id, rule_id, msg, data, severity),
        )
        conn.commit()
    except Error as e:
        print(f"[DB ERROR] add_modsec_alert failed: {e}")

def process_line(line, modsec_pending):
    """
    ログ1行をパースし、アクセス記録・ModSecurity検知・URL登録を行う
    blocked_by_modsecは「ModSecurity: Access denied」が行内または直前行に含まれるかで判定
    """
    # ModSecurity詳細行かどうか判定
    if MODSEC_BLOCK_PATTERN.search(line):
        # ModSecurity: Access denied の行は一時保存し、次のリクエスト行で利用
        modsec_pending['line'] = line
        return

    match = LOG_PATTERN.search(line)
    if match:
        ip = match.group("ip")
        method = match.group("method")
        url = match.group("url")
        status = match.group("status")
        log_time_str = match.group("time")
        # NGINXログの日時形式: 21/Jun/2025:23:33:33 +0900
        try:
            access_time = datetime.strptime(log_time_str, "%d/%b/%Y:%H:%M:%S %z")
        except Exception as e:
            print(f"[WARN] Failed to parse log time '{log_time_str}': {e}")
            access_time = datetime.now()

        supported_methods = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"}
        if method not in supported_methods:
            print(f"[WARN] Unsupported HTTP method detected: {method}. Skipping entry.")
            return

        # 直前にModSecurity: Access denied行があればblocked扱い
        blocked = False
        modsec_line = None
        if modsec_pending.get('line'):
            blocked = True
            modsec_line = modsec_pending['line']
            modsec_pending['line'] = None

        try:
            add_access_log(method, url, status, ip, access_time, blocked)
            if blocked and modsec_line:
                conn = db_connect()
                cursor = conn.cursor()
                cursor.execute("SELECT MAX(id) FROM access_log")
                log_id = cursor.fetchone()[0]
                # ModSecurity詳細情報を抽出して登録
                for rule_match in MODSEC_RULE_PATTERN.finditer(modsec_line):
                    add_modsec_alert(
                        log_id,
                        rule_match.group("id"),
                        rule_match.group("msg"),
                        rule_match.group("data"),
                        rule_match.group("severity"),
                    )
            add_registry_entry(method, url, ip, access_time)
        except Error as e:
            print(f"[DB ERROR] Failed to log line: {e}")

# nginxログをリアルタイム監視（10秒ごとに設定も再取得）
def tail_log():
    """
    nginxログファイルをリアルタイムで監視し、追記がなければ一定時間待機する。
    ログが高速に追記される場合や、ログローテーション時も取りこぼしを防ぐ。
    """
    log_path = LOG_PATH
    last_inode = None
    empty_read_count = 0
    max_empty_reads = 50  # 連続で空読みしたらファイルを再オープン

    def get_inode(path):
        try:
            return os.stat(path).st_ino
        except Exception:
            return None

    modsec_pending = {'line': None}  # 直前のModSecurity: Access denied行を保持

    while True:
        try:
            with open(log_path, "r") as f:
                f.seek(0, 2)
                last_inode = get_inode(log_path)
                while True:
                    if int(time.time()) % 10 == 0:
                        fetch_settings()
                    line = f.readline()
                    if not line:
                        empty_read_count += 1
                        time.sleep(0.1)
                        # ログローテーションやinode変更を検知
                        current_inode = get_inode(log_path)
                        if current_inode != last_inode:
                            # ファイルが切り替わった場合は再オープン
                            break
                        if empty_read_count >= max_empty_reads:
                            # しばらく新規行がなければ再オ���プン
                            break
                        continue
                    empty_read_count = 0
                    process_line(line, modsec_pending)
        except FileNotFoundError:
            print(f"[ERROR] Log file not found: {log_path}")
            time.sleep(1)
        except Exception as e:
            print(f"[ERROR] tail_log exception: {e}")
            time.sleep(1)

# メイン関数（初期化 → 設定読込 → ログ監視）
def rescan_attack_types():
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("SELECT id, full_url FROM url_registry")
        rows = cursor.fetchall()
        for entry_id, url in rows:
            new_type = detect_attack_type(url)
            cursor.execute("UPDATE url_registry SET attack_type = %s WHERE id = %s", (new_type, entry_id))
        conn.commit()
        print(f"[{datetime.now()}] [INFO] attack_type fields have been updated based on latest patterns.")
    except Error as e:
        print(f"[{datetime.now()}] [DB ERROR] rescan_attack_types failed: {e}")

def main():
    # アプリ情報の表示
    print(f"==== {APP_NAME} ====")
    print(f"Version: {APP_VERSION}")
    print(f"{APP_AUTHOR}\n")

    parser = argparse.ArgumentParser(description='NGINX log monitor to MySQL')
    parser.add_argument('--skip-init-db', action='store_true', help='Skip automatic DB initialization')
    args = parser.parse_args()

    rescan_attack_types()
    if not args.skip_init_db:
        if not init_db():
            print("[ERROR] Database init failed. Exiting.")
            sys.exit(1)
        print("[INFO] Database schema verified or created.")

    fetch_settings()
    print("Starting log monitor...")
    tail_log()

# エントリーポイント
if __name__ == "__main__":
    main()
