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
# 例: 192.168.10.11 - - [21/Jun/2025:23:33:33 +0900] "GET /epgstation/..." 200
# [^\]]+ の \] は正規表現上必要なエスケープです。linterのW605警告は無視してください。 # noqa: W605
LOG_PATTERN = re.compile(
    r'(?P<ip>\d+\.\d+\.\d+\.\d+)\s-\s-\s'
    r'\[(?P<time>[^\]]+)\]\s"(?P<method>GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH)\s'
    r'(?P<url>[^ ]+)\sHTTP.*?"\s(?P<status>\d{3})'
)  # noqa: W605

# DB再接続の最大試行回数と待機時間
MAX_RETRIES = 5
RETRY_DELAY = 3

# グローバルDB接続セッション
DB_SESSION = None


# タイムスタンプ＋ログレベル付きで標準出力に出す共通関数
def log(msg, level="INFO"):
    """
    ログ出力用共通関数。タイムスタンプとレベルを付与して出力する。
    """
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{now}][{level}] {msg}")


# DBの接続情報を復号化して取得
def load_db_config():
    with open(KEY_PATH, 'rb') as key_file:
        key = key_file.read()
    fernet = Fernet(key)
    with open(SECURE_CONFIG_PATH, 'rb') as enc_file:
        decrypted = fernet.decrypt(enc_file.read())
    return json.loads(decrypted)


# DBへ接続（失敗時は最大N回リトライ）
def db_connect():
    """
    DBへ接続する関数。失敗時は最大N回リトライし、全て失敗した場合はNoneを返す。
    """
    global DB_SESSION
    if DB_SESSION is not None and DB_SESSION.is_connected():
        return DB_SESSION

    log("Attempting to connect to the database...", "INFO")
    config = load_db_config()
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            conn = mysql.connector.connect(**config)
            if conn.is_connected():
                DB_SESSION = conn
                if attempt > 1:
                    log(f"DB connection recovered on retry #{attempt}.", "RECOVERED")
                    log(f"DB connection successful on retry #{attempt}.", "INFO")
                else:
                    log("Database connection established successfully.", "INFO")
                return DB_SESSION
        except Error as e:
            log(f"Connection attempt {attempt} failed: {e}", "DB ERROR")
            if attempt == MAX_RETRIES:
                log("Max retries exceeded. Exiting.", "DB ERROR")
                sys.exit(1)
            time.sleep(RETRY_DELAY)
    # すべてのリトライが失敗した場合はNoneを返す
    return None


# DBの初期テーブル構造を作成（なければ）
# ModSecurityアラート詳細保存用テーブルを作成（ブロック理由など）
MODSEC_ALERT_TABLE = "modsec_alerts"


def init_db():
    """
    DB初期化処理。テーブル・カラムの存在確認と作成、attack_patterns.jsonバージョン管理。
    """
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("UPDATE settings SET backend_version = %s WHERE id = 1", (APP_VERSION,))
        # attack_patterns.jsonのバージョンをsettingsテーブルに格納
        try:
            with open(ATTACK_PATTERNS_PATH, "r") as f:
                local_patterns = json.load(f)
            version = local_patterns.get("version", "")
            if version:
                log(f"attack_patterns.jsonのバージョン情報を取得: {version}", "INFO")
            else:
                log("attack_patterns.jsonのバージョン情報の取得に失敗（versionキーが空または未定義）", "WARN")
            cursor.execute(
                "SHOW COLUMNS FROM settings LIKE 'attack_patterns_version'"
            )
            if cursor.fetchone() is None:
                cursor.execute("ALTER TABLE settings ADD COLUMN attack_patterns_version VARCHAR(50)")
        except Exception as e:
            version = ""
            log(f"attack_patterns.jsonのバージョン情報の取得に失敗: {e}", "WARN")
            pass

        # settingsテーブルが既存ならUPDATE、なければINSERT
        try:
            cursor.execute("SELECT COUNT(*) FROM settings")
            if cursor.fetchone()[0] == 0:
                cursor.execute("""
                    INSERT INTO settings (
                        id, whitelist_mode, whitelist_ip,
                        backend_version, frontend_version,
                        frontend_last_login, frontend_last_ip,
                        attack_patterns_version
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                """, (1, False, '', APP_VERSION, '', None, '', version))
            else:
                cursor.execute(
                    "UPDATE settings SET attack_patterns_version = %s WHERE id = 1",
                    (version,)
                )
        except Exception as e:
            log(f"attack_patterns.jsonのバージョン取得・保存に失敗: {e}", "INFO")
        log(f"backend_version updated to {APP_VERSION} in settings table.", "INFO")
        conn.commit()
    except Error as e:
        log(f"Failed to update backend_version: {e}", "DB ERROR")
        return False  # ここでreturnを追加
    except Exception as e:
        # 予期しない例外も捕捉し、Falseを返す
        log(f"Unexpected error in init_db: {e}", "ERROR")
        return False
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
                frontend_last_ip VARCHAR(45),
                attack_patterns_version VARCHAR(50)
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
                    frontend_last_login, frontend_last_ip,
                    attack_patterns_version
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """, (1, False, '', APP_VERSION, '', None, '', ''))
        conn.commit()
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
                    log(
                        f"Adding missing column '{column_name}' to {table} "
                        f"using DDL: ALTER TABLE {table} ADD COLUMN {column_name} {ddl}",
                        "INFO"
                    )
                    cursor.execute(f"ALTER TABLE {table} ADD COLUMN {column_name} {ddl}")
                    conn.commit()
        # --- ここからユーザー認証用テーブル追加 ---
        # usersテーブル（フロントエンド認証用）
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(64) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                email VARCHAR(255) NOT NULL UNIQUE,
                is_active BOOLEAN DEFAULT TRUE,
                created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                last_login_at DATETIME
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
        # login_historyテーブル（ログイン履歴）
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS login_history (
                id INT AUTO_INCREMENT PRIMARY KEY,
                user_id INT NOT NULL,
                login_ip VARCHAR(45) NOT NULL,
                login_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                success BOOLEAN NOT NULL,
                user_agent TEXT,
                FOREIGN KEY (user_id) REFERENCES users(id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """)
        # --- ここまで追加 ---

        conn.commit()
    except Error as e:
        log(f"init_db failed: {e}", "DB ERROR")
        return False
    except Exception as e:
        # 予期しない例外も捕捉し、Falseを返す
        log(f"Unexpected error in init_db (table creation): {e}", "ERROR")
        return False
    return True


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
        log(f"fetch_settings failed: {e}", "DB ERROR")


# 未登録のURLをurl_registryに登録（ホワイトリストIPはwhitelistとして登録）
def add_registry_entry(method, url, ip, access_time):
    """
    url_registryへURLを登録する。ホワイトリストIPの場合はis_whitelistedも設定。
    created_at/updated_atにはアクセス時刻（ログから取得）使用。
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
                INSERT INTO url_registry (
                    method, full_url, created_at, is_whitelisted, description, updated_at, attack_type
                )
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
            log(f"Updated whitelist status for URL: {url}", "INFO")
    except Error as e:
        log(f"add_registry_entry failed: {e}", "DB ERROR")


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
            (
                method,
                url,
                int(status),
                ip,
                access_time,
                blocked,
            ),
        )
        conn.commit()
    except Error as e:
        log(f"add_access_log failed: {e}", "DB ERROR")


# ログ1行を処理：アクセス保存＋新URL検出
# URLに含まれる攻撃タイプを識別
ATTACK_PATTERNS_PATH = "/run/secrets/attack_patterns.json"


def detect_attack_type(url):
    """
    URLから攻撃タイプを判定する。attack_patterns.jsonの"version"キーは無視する。
    """
    try:
        url = unquote(url)
        with open(ATTACK_PATTERNS_PATH, "r") as f:
            patterns = json.load(f)
        matches = []
        for attack_name, pattern in patterns.items():
            # "version"キーはシグネチャ判定から除外
            if attack_name == "version":
                continue
            if re.search(pattern, url, re.IGNORECASE):
                matches.append(attack_name)
        if matches:
            return ",".join(matches)
    except Exception as e:
        log(f"Failed to detect attack type: {e}", "ERROR")
    return None


# ModSecurityによるブロックを判定するキーワード
MODSEC_BLOCK_PATTERN = re.compile(r"ModSecurity: Access denied", re.IGNORECASE)

# ModSecurityルール詳細を抽出しmodsec_alertsに記録
# 正規表現の ] はエスケープ不要なので \] を外す
# [.*?] の ] は正規表現上エスケープ不要ですが、linterのW605警告が出る場合は # noqa: W605 で除外してください。
MODSEC_RULE_PATTERN = re.compile(
    r'ModSecurity: Access denied.*?'
    r'\[id "(?P<id>\d+)"\].*?'
    r'\[msg "(?P<msg>.*?)"\].*?'
    r'\[data "(?P<data>.*?)"\].*?'
    r'\[severity "(?P<severity>\d+)"\]',
    re.IGNORECASE | re.DOTALL,
)  # noqa: W605


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
        log(f"add_modsec_alert failed: {e}", "DB ERROR")


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
            access_time = datetime.strptime(
                log_time_str, "%d/%b/%Y:%H:%M:%S %z"
            )
        except Exception as e:
            log(f"Failed to parse log time '{log_time_str}': {e}", "WARN")
            access_time = datetime.now()

        supported_methods = {"GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH"}
        if method not in supported_methods:
            log(f"Unsupported HTTP method detected: {method}. Skipping entry.", "WARN")
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
            log(f"Failed to log line: {e}", "DB ERROR")


# nginxログをリアルタイム監視（10秒ごとに設定も再取得）
def tail_log():
    """
    nginxログファイルをリアルタイムで監視し、追記がなければ一定時間待機する。
    ログが高速追記される場合や、ローテーション時も取りこぼしを防ぐ。
    """
    log_path = LOG_PATH
    empty_read_count = 0
    max_empty_reads = 50  # 連続で空読みしたらファイルを再オープン

    def get_inode(path):
        try:
            return os.stat(path).st_ino
        except OSError:
            # ファイルが存在しない等のOSエラーのみ捕捉
            return None

    modsec_pending = {'line': None}  # 直前のModSecurity: Access denied行を保持

    while True:
        try:
            with open(log_path, "r") as f:
                f.seek(0, 2)
                last_inode_val = get_inode(log_path)
                while True:
                    if int(time.time()) % 10 == 0:
                        fetch_settings()
                    line = f.readline()
                    if not line:
                        empty_read_count += 1
                        time.sleep(0.1)
                        # ログローテーションやinode変更を検知
                        current_inode = get_inode(log_path)
                        if current_inode != last_inode_val:
                            # ファイルが切り替わった場合は再オープン
                            break
                        if empty_read_count >= max_empty_reads:
                            # しばらく新規行がなければ再オープン
                            break
                        continue
                    empty_read_count = 0
                    process_line(line, modsec_pending)
        except FileNotFoundError:
            log(f"Log file not found: {log_path}", "ERROR")
            time.sleep(1)
        except OSError as e:
            # OSErrorのみを捕捉し、他の例外は上位で捕捉
            log(f"tail_log OSError: {e}", "ERROR")
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
            cursor.execute(
                "UPDATE url_registry SET attack_type = %s WHERE id = %s",
                (new_type, entry_id)
            )
        conn.commit()
        log("attack_type fields have been updated based on latest patterns.", "INFO")
    except Error as e:
        log(f"rescan_attack_types failed: {e}", "DB ERROR")


def main():
    """
    メイン関数。アプリ情報表示、引数処理、DB初期化、設定取得、ログ監視を行う。
    """
    print(f"==== {APP_NAME} ====")
    print(f"Version: {APP_VERSION}")
    print(f"{APP_AUTHOR}")
    print()  # 空行はそのまま

    parser = argparse.ArgumentParser(description='NGINX log monitor to MySQL')
    parser.add_argument('--skip-init-db', action='store_true', help='Skip automatic DB initialization')
    args = parser.parse_args()

    rescan_attack_types()
    if not args.skip_init_db:
        if not init_db():
            log("Database init failed. Exiting.", "ERROR")
            sys.exit(1)
        log("Database schema verified or created.", "INFO")

    fetch_settings()
    log("Starting log monitor...", "INFO")
    tail_log()


# エントリーポイント
if __name__ == "__main__":
    main()

