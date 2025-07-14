"""
DBスキーマ管理モジュール
DBの初期テーブル構造作成・カラム存在確認・追加機能を提供
PEP8/日本語コメント/スネークケース
"""
import mysql.connector
from mysql.connector import Error
import bcrypt
import json

# ModSecurityアラート詳細保存用テーブル名
MODSEC_ALERT_TABLE = "modsec_alerts"


def create_initial_tables(conn, app_version, log_func=None):
    """
    DB初期化処理。テーブル・カラムの存在確認と作成。
    usersテーブル新規作成時は初期ユーザー(admin)を自動追加する。
    :param conn: MySQLコネクション
    :param app_version: バックエンドバージョン
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()

    # settingsテーブル
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS settings (
            id INT PRIMARY KEY,
            whitelist_mode BOOLEAN DEFAULT FALSE,
            whitelist_ip VARCHAR(45) DEFAULT '',
            backend_version VARCHAR(50) DEFAULT '',
            frontend_version VARCHAR(50) DEFAULT ''
        )
    """)
    log("settingsテーブルを作成または確認しました", "INFO")

    # url_registryテーブル（ユーザーの脅威最終判断とメモ欄を追加）
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS url_registry (
            id INT AUTO_INCREMENT PRIMARY KEY,
            method VARCHAR(10),
            full_url TEXT,
            created_at DATETIME,
            updated_at DATETIME,
            is_whitelisted BOOLEAN DEFAULT FALSE,
            description TEXT,
            attack_type VARCHAR(255),
            user_final_threat BOOLEAN DEFAULT NULL,
            user_threat_note TEXT
        )
    """)
    log("url_registryテーブルを作成または確認しました", "INFO")

    # access_logテーブル
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS access_log (
            id INT AUTO_INCREMENT PRIMARY KEY,
            method VARCHAR(10),
            full_url TEXT,
            status_code INT,
            ip_address VARCHAR(45),
            access_time DATETIME,
            blocked_by_modsec BOOLEAN DEFAULT FALSE
        )
    """)
    log("access_logテーブルを作成または確認しました", "INFO")

    # modsec_alertsテーブル
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
    log("modsec_alertsテーブルを作成または確認しました", "INFO")

    # usersテーブル（ユーザー認証用）
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
    log("usersテーブルを作成または確認しました", "INFO")

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
    log("login_historyテーブルを作成または確認しました", "INFO")

    # settingsテーブルが空なら初期レコード挿入
    cursor.execute("SELECT COUNT(*) FROM settings")
    if cursor.fetchone()[0] == 0:
        cursor.execute("""
            INSERT INTO settings (
                id, whitelist_mode, whitelist_ip, backend_version, frontend_version
            ) VALUES (%s, %s, %s, %s, %s)
        """, (1, False, '', app_version, ''))
        log("settingsテーブルに初期レコードを挿入しました", "INFO")

    # usersテーブルが空なら初期ユーザー(admin)を追加
    cursor.execute("SELECT COUNT(*) FROM users")
    if cursor.fetchone()[0] == 0:
        # デフォルトパスワード 'admin' をハッシュ化
        password_hash = bcrypt.hashpw('admin'.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')
        cursor.execute("""
            INSERT INTO users (username, password_hash, email, is_active)
            VALUES (%s, %s, %s, %s)
        """, ('admin', password_hash, 'admin@example.com', True))
        log("初期ユーザー(admin)を作成しました", "INFO")

    conn.commit()


def ensure_all_required_columns(conn, log_func=None):
    """
    主要テーブルのカラム存在確認・不足カラム自動追加
    :param conn: MySQLコネクション
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()

    # url_registryテーブルの必要カラム確��・追加
    required_columns = {
        'user_final_threat': 'BOOLEAN DEFAULT NULL',
        'user_threat_note': 'TEXT'
    }

    for column_name, column_def in required_columns.items():
        cursor.execute(f"SHOW COLUMNS FROM url_registry LIKE '{column_name}'")
        if cursor.fetchone() is None:
            cursor.execute(f"ALTER TABLE url_registry ADD COLUMN {column_name} {column_def}")
            log(f"url_registryテーブルに{column_name}カラムを追加しました", "INFO")

    # settingsテーブルの必要カラム確認・追加
    settings_columns = {
        'attack_patterns_version': 'VARCHAR(50) DEFAULT ""'
    }

    for column_name, column_def in settings_columns.items():
        cursor.execute(f"SHOW COLUMNS FROM settings LIKE '{column_name}'")
        if cursor.fetchone() is None:
            cursor.execute(f"ALTER TABLE settings ADD COLUMN {column_name} {column_def}")
            log(f"settingsテーブルに{column_name}カラムを追加しました", "INFO")

    conn.commit()


def drop_deprecated_settings_columns(conn, log_func=None):
    """
    settingsテーブルから廃止カラムを削除
    frontend_last_login, frontend_last_ipを削除
    :param conn: MySQLコネクション
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()

    # 廃止カラムリスト
    deprecated_columns = ['frontend_last_login', 'frontend_last_ip']

    for column_name in deprecated_columns:
        try:
            cursor.execute(f"SHOW COLUMNS FROM settings LIKE '{column_name}'")
            if cursor.fetchone() is not None:
                cursor.execute(f"ALTER TABLE settings DROP COLUMN {column_name}")
                log(f"settingsテーブルから廃止カラム{column_name}を削除しました", "INFO")
        except Error as e:
            log(f"廃止カラム{column_name}の削除に失敗: {e}", "WARN")

    conn.commit()


def update_settings_version(conn, attack_patterns_path, app_version, log_func=None):
    """
    settingsテーブルのバージョン情報を更新
    :param conn: MySQLコネクション
    :param attack_patterns_path: attack_patterns.jsonのパス
    :param app_version: アプリケーションバージョン
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()

    # attack_patterns.jsonのバージョン情報を取得
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            local_patterns = json.load(f)
        attack_patterns_version = local_patterns.get("version", "")
    except Exception as e:
        log(f"attack_patterns.jsonのバージョン情報の取得に失敗: {e}", "WARN")
        attack_patterns_version = ""

    # settingsテーブルのバージョン情報を更新
    try:
        cursor.execute("SELECT COUNT(*) FROM settings WHERE id = 1")
        if cursor.fetchone()[0] == 0:
            cursor.execute("""
                INSERT INTO settings (
                    id, whitelist_mode, whitelist_ip, backend_version, 
                    frontend_version, attack_patterns_version
                ) VALUES (%s, %s, %s, %s, %s, %s)
            """, (1, False, '', app_version, '', attack_patterns_version))
        else:
            cursor.execute("""
                UPDATE settings SET 
                    backend_version = %s,
                    attack_patterns_version = %s
                WHERE id = 1
            """, (app_version, attack_patterns_version))

        conn.commit()
        log(f"settingsテーブルのバージョン情報を更新しました (backend: {app_version}, attack_patterns: {attack_patterns_version})", "INFO")

    except Error as e:
        log(f"settingsテーブルのバージョン情報更新に失敗: {e}", "DB ERROR")
