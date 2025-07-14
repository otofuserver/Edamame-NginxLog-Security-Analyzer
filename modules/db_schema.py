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
            role_id INT DEFAULT NULL,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
            last_login_at DATETIME,
            FOREIGN KEY (role_id) REFERENCES roles(id)
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

    # rolesテーブル（ロール管理）
    cursor.execute("""
        CREATE TABLE IF NOT EXISTS roles (
            id INT AUTO_INCREMENT PRIMARY KEY,
            role_name VARCHAR(50) NOT NULL UNIQUE,
            description TEXT,
            created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    log("rolesテーブルを作成または確認しました", "INFO")

    # settingsテーブルが空なら初期レコード挿入
    cursor.execute("SELECT COUNT(*) FROM settings")
    if cursor.fetchone()[0] == 0:
        cursor.execute("""
            INSERT INTO settings (
                id, whitelist_mode, whitelist_ip, backend_version, frontend_version
            ) VALUES (%s, %s, %s, %s, %s)
        """, (1, False, '', app_version, ''))
        log("settingsテーブルに初期レコードを挿入しました", "INFO")

    # rolesテーブルが空なら初期ロールを追加
    cursor.execute("SELECT COUNT(*) FROM roles")
    if cursor.fetchone()[0] == 0:
        initial_roles = [
            ('administrator', '管理者：すべての機能にアクセス可能'),
            ('monitor', '監視メンバー：ログ閲覧と基本的な分析機能のみ')
        ]
        for role_name, description in initial_roles:
            cursor.execute("""
                INSERT INTO roles (role_name, description)
                VALUES (%s, %s)
            """, (role_name, description))
        log("初期ロール（administrator, monitor）を作成しました", "INFO")

    # usersテーブルが空なら初期ユーザー(admin)を追加
    cursor.execute("SELECT COUNT(*) FROM users")
    if cursor.fetchone()[0] == 0:
        # デフォルトパスワード 'admin123' をハッシュ化
        password_hash = bcrypt.hashpw('admin123'.encode('utf-8'), bcrypt.gensalt()).decode('utf-8')

        # 管理者ロールのIDを取得
        cursor.execute("SELECT id FROM roles WHERE role_name = 'administrator'")
        admin_role_id = cursor.fetchone()[0]

        cursor.execute("""
            INSERT INTO users (username, password_hash, email, is_active, role_id)
            VALUES (%s, %s, %s, %s, %s)
        """, ('admin', password_hash, 'admin@example.com', True, admin_role_id))
        log("初期ユーザー(admin)を管理者ロールで作成しました", "INFO")

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

    # usersテーブルの必要カラム確認・追加（ロール管理）
    users_columns = {
        'role_id': 'INT DEFAULT NULL'
    }

    for column_name, column_def in users_columns.items():
        cursor.execute(f"SHOW COLUMNS FROM users LIKE '{column_name}'")
        if cursor.fetchone() is None:
            cursor.execute(f"ALTER TABLE users ADD COLUMN {column_name} {column_def}")
            log(f"usersテーブルに{column_name}カラムを追加しました", "INFO")

            # 外部キー制約を追加
            try:
                cursor.execute("""
                    ALTER TABLE users 
                    ADD CONSTRAINT fk_users_role_id 
                    FOREIGN KEY (role_id) REFERENCES roles(id)
                """)
                log("usersテーブルにrole_idの外部キー制約を追加しました", "INFO")
            except Error as e:
                log(f"外部キー制約の追加に失敗: {e}", "WARN")

    # 既存のadminユーザーに管理者ロールを設定
    try:
        cursor.execute("SELECT id FROM users WHERE username = 'admin' AND role_id IS NULL")
        admin_user = cursor.fetchone()
        if admin_user:
            cursor.execute("SELECT id FROM roles WHERE role_name = 'administrator'")
            admin_role = cursor.fetchone()
            if admin_role:
                cursor.execute("UPDATE users SET role_id = %s WHERE username = 'admin'", (admin_role[0],))
                log("既存のadminユーザーに管理者ロールを設定しました", "INFO")
    except Error as e:
        log(f"adminユーザーのロール設定に失敗: {e}", "WARN")

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


def get_user_role(conn, user_id, log_func=None):
    """
    ユーザーのロール情報を取得
    :param conn: MySQLコネクション
    :param user_id: ユーザーID
    :param log_func: ログ出力用関数（省略可）
    :return: ロール情報の辞書（ロールが見つからない場合はNone）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT r.id, r.role_name, r.description
            FROM users u
            JOIN roles r ON u.role_id = r.id
            WHERE u.id = %s
        """, (user_id,))

        result = cursor.fetchone()
        if result:
            return {
                'role_id': result[0],
                'role_name': result[1],
                'description': result[2]
            }
        return None

    except Error as e:
        log(f"ユーザーロール取得に失敗: {e}", "DB ERROR")
        return None


def update_user_role(conn, user_id, role_name, log_func=None):
    """
    ユーザーのロールを更新
    :param conn: MySQLコネクション
    :param user_id: ユーザーID
    :param role_name: 新しいロール名
    :param log_func: ログ出力用関数（省略可）
    :return: 更新成功時True、失敗時False
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = conn.cursor()

        # ロールIDを取得
        cursor.execute("SELECT id FROM roles WHERE role_name = %s", (role_name,))
        role_result = cursor.fetchone()
        if not role_result:
            log(f"指定されたロール '{role_name}' が見つかりません", "WARN")
            return False

        role_id = role_result[0]

        # ユーザーのロールを更新
        cursor.execute("UPDATE users SET role_id = %s WHERE id = %s", (role_id, user_id))

        if cursor.rowcount > 0:
            conn.commit()
            log(f"ユーザーID {user_id} のロールを '{role_name}' に更新しました", "INFO")
            return True
        else:
            log(f"ユーザーID {user_id} が見つかりません", "WARN")
            return False

    except Error as e:
        log(f"ユーザーロール更新に失敗: {e}", "DB ERROR")
        return False


def list_all_roles(conn, log_func=None):
    """
    すべてのロール一覧を取得
    :param conn: MySQLコネクション
    :param log_func: ログ出力用関数（省略可）
    :return: ロール一覧のリスト
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = conn.cursor()
        cursor.execute("SELECT id, role_name, description FROM roles ORDER BY id")

        roles = []
        for row in cursor.fetchall():
            roles.append({
                'id': row[0],
                'role_name': row[1],
                'description': row[2]
            })

        return roles

    except Error as e:
        log(f"ロール一��取得に失敗: {e}", "DB ERROR")
        return []


def get_users_with_roles(conn, log_func=None):
    """
    ユーザー一覧とそのロール情報を取得
    :param conn: MySQLコネクション
    :param log_func: ログ出力用関数（省略可）
    :return: ユーザー情報とロール情報のリスト
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = conn.cursor()
        cursor.execute("""
            SELECT u.id, u.username, u.email, u.is_active, u.created_at, 
                   r.role_name, r.description as role_description
            FROM users u
            LEFT JOIN roles r ON u.role_id = r.id
            ORDER BY u.id
        """)

        users = []
        for row in cursor.fetchall():
            users.append({
                'id': row[0],
                'username': row[1],
                'email': row[2],
                'is_active': row[3],
                'created_at': row[4],
                'role_name': row[5],
                'role_description': row[6]
            })

        return users

    except Error as e:
        log(f"ユーザー一覧取得に失敗: {e}", "DB ERROR")
        return []

