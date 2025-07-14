#!/usr/bin/env python3
"""
NGINXログ監視・解析メインモジュール
ログの監視とDB保存を担当
PEP8/日本語コメント/スネークケース
"""
import time
import argparse
import json
import sys
import os
from datetime import datetime
import mysql.connector
from mysql.connector import Error
from cryptography.fernet import Fernet

# モジュールパスを動的に追加（相対パス対応）
current_dir = os.path.dirname(os.path.abspath(__file__))
modules_dir = os.path.join(current_dir, 'modules')
if modules_dir not in sys.path:
    sys.path.insert(0, modules_dir)

# 変数の事前定義（インポートエラー時のデフォルト値）
APP_NAME = "Edamame NginxLog Security Analyzer"
APP_VERSION = "v1.0.0"
APP_AUTHOR = "Developed by Code Copilot"
LOG_PATH = "/var/log/nginx/nginx.log"
SECURE_CONFIG_PATH = "/run/secrets/db_config_enc"
KEY_PATH = "/run/secrets/db_secret_key"
ATTACK_PATTERNS_PATH = "/run/secrets/attack_patterns.json"
MAX_RETRIES = 5
RETRY_DELAY = 3
ATTACK_PATTERNS_CHECK_INTERVAL = 3600

# modulesからの機能インポート
try:
    from modules.config import (
        APP_NAME, APP_VERSION, APP_AUTHOR, LOG_PATH, SECURE_CONFIG_PATH,
        KEY_PATH, ATTACK_PATTERNS_PATH, MAX_RETRIES, RETRY_DELAY,
        ATTACK_PATTERNS_CHECK_INTERVAL
    )
    from modules.db_schema import (
        create_initial_tables, ensure_all_required_columns,
        drop_deprecated_settings_columns
    )
    from modules.log_parser import parse_log_line
    from modules.modsec_handler import detect_modsec_block, parse_modsec_alert, save_modsec_alerts
    from modules.attack_pattern import detect_attack_type, update_if_needed

    # インポート成功のログ
    import_success = True

except ImportError as e:
    print(f"[WARN] モジュールのインポートに失敗しました: {e}")
    print(f"[WARN] デフォルト設定値を使用します")

    # インポートエラー時も設定変数が確実に利用できるよう再定義
    APP_NAME = "Edamame NginxLog Security Analyzer"
    APP_VERSION = "v1.0.0"
    APP_AUTHOR = "Developed by Code Copilot"
    LOG_PATH = "/var/log/nginx/nginx.log"
    SECURE_CONFIG_PATH = "/run/secrets/db_config_enc"
    KEY_PATH = "/run/secrets/db_secret_key"
    ATTACK_PATTERNS_PATH = "/run/secrets/attack_patterns.json"
    MAX_RETRIES = 5
    RETRY_DELAY = 3
    ATTACK_PATTERNS_CHECK_INTERVAL = 3600

    # インポートに失敗した場合のダミー関数定義
    def create_initial_tables(conn, app_version, log_func=None):
        if log_func:
            log_func("モジュール未ロード: create_initial_tables はスキップされました", "WARN")
        return False

    def ensure_all_required_columns(conn, log_func=None):
        if log_func:
            log_func("モジュール未ロード: ensure_all_required_columns はスキップされました", "WARN")
        return False

    def drop_deprecated_settings_columns(conn, log_func=None):
        if log_func:
            log_func("モジュール未ロード: drop_deprecated_settings_columns はスキップされました", "WARN")
        return False

    def parse_log_line(line, log_func=None):
        if log_func:
            log_func("モジュール未ロード: parse_log_line はスキップされました", "WARN")
        return None

    def detect_modsec_block(line):
        return False

    def parse_modsec_alert(line):
        return []

    def save_modsec_alerts(conn, log_id, alerts, log_func=None):
        if log_func:
            log_func("モジュール未ロード: save_modsec_alerts はスキップされました", "WARN")
        return False

    def detect_attack_type(url, patterns_path):
        return "unknown"

    def update_if_needed(patterns_path, log_func=None):
        if log_func:
            log_func("モジュール未ロード: update_if_needed はスキップされました", "WARN")
        return False

    import_success = False

# グローバル変数
DB_SESSION = None  # DBコネクション
WHITELIST_MODE = False  # ホワイトリストモード設定
WHITELIST_IP = ""  # ホワイトリストIP
LAST_ATTACK_PATTERNS_CHECK = 0  # 最終attack_patternsチェック時刻



# タイムスタンプ＋ログレベル付きで標準出力に出す共通関数
def log(msg, level="INFO"):
    """
    ログ出力用共通関数。タイムスタンプとレベルを付与して出力する。
    DEBUG レベルの出力を制御し、本番環境での可読性を向上。
    """
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    # DEBUG レベルのログは環境変数で制御可能
    if level == "DEBUG":
        debug_enabled = os.environ.get("NGINX_LOG_DEBUG", "false").lower() == "true"
        if not debug_enabled:
            return
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
# MODSEC_ALERT_TABLE = "modsec_alerts" ← modules/db_schema.pyに移行済み


def init_db():
    """
    DB初期化処理。テーブル・カラムの存在確認と作成。
    usersテーブル新規作成時は初期ユーザー(admin)を自動追加する。
    """
    try:
        conn = db_connect()
        if not conn:
            return False

        # --- テーブル作成・カラム追加処理をmodules/db_schema.pyへ委譲 ---
        create_initial_tables(conn, APP_VERSION, log_func=log)

        # 主要テーブルのカラム存在確認・不足カラム自動追加もmodules/db_schema.pyへ委譲
        ensure_all_required_columns(conn, log_func=log)

        # settingsテーブルからfrontend_last_login, frontend_last_ipの廃止カラム削除
        drop_deprecated_settings_columns(conn, log_func=log)

        # settingsテーブルのバージョン情報を更新
        from modules.db_schema import update_settings_version
        update_settings_version(conn, ATTACK_PATTERNS_PATH, APP_VERSION, log_func=log)

        # attack_patterns.jsonの自動バージョンチェック・更新
        update_attack_patterns_if_needed()

        conn.commit()
        log("Database schema verified or created successfully.", "INFO")
        return True

    except Error as e:
        log(f"init_db failed: {e}", "DB ERROR")
        return False
    except Exception as e:
        log(f"Unexpected error in init_db: {e}", "ERROR")
        return False


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
    URLはデコードした状態で保存する。
    """
    from modules.attack_pattern import decode_url

    # URLをデコードしてから保存
    decoded_url = decode_url(url)
    attack_type = detect_attack_type(decoded_url, ATTACK_PATTERNS_PATH)

    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("SELECT id, is_whitelisted FROM url_registry WHERE full_url=%s", (decoded_url,))
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
                (method, decoded_url, access_time, is_whitelisted, access_time, attack_type),
            )
            conn.commit()
        elif is_whitelisted and not row[1]:
            cursor.execute(
                "UPDATE url_registry SET is_whitelisted = TRUE, updated_at = %s WHERE full_url = %s",
                (access_time, decoded_url),
            )
            conn.commit()
            log(f"Updated whitelist status for URL: {decoded_url}", "INFO")
    except Error as e:
        log(f"add_registry_entry failed: {e}", "DB ERROR")


# 全アクセスをaccess_logに追記
def add_access_log(method, url, status, ip, access_time, blocked=False):
    """
    アクセスログをDBに保存
    URLはデコードした状態で保存する
    :param method: HTTPメソッド
    :param url: アクセスURL
    :param status: ステータスコード
    :param ip: クライアントIP
    :param access_time: アクセス時刻
    :param blocked: ModSecurityブロックの有無
    """
    from modules.attack_pattern import decode_url

    # URLをデコードしてから保存
    decoded_url = decode_url(url)

    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO access_log (method, full_url, status_code, ip_address, access_time, blocked_by_modsec)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (method, decoded_url, int(status), ip, access_time, blocked)
        )
        conn.commit()
    except Error as e:
        log(f"add_access_log failed: {e}", "DB ERROR")


# ログ1行を処理：アクセス保存＋新URL検出
def process_line(line, modsec_pending):
    """
    ログ1行をパースし、アクセス記録・ModSecurity検知・URL登録を行う。
    blocked_by_modsecは「ModSecurity: Access denied」が行内または直前行に含まれるかで判定。
    """
    # ModSecurity詳細行かどうか判定
    if detect_modsec_block(line):
        # ModSecurity: Access denied の行は一時保存し、次のリクエスト行で利用
        modsec_pending['line'] = line
        return True  # ModSecurity行として処理済み

    # ログ行をパース
    log_data = parse_log_line(line, log_func=log)
    if not log_data:
        return None  # パースに失敗

    # 直前にModSecurity: Access denied行があればblocked扱い
    blocked = False
    modsec_line = None
    if modsec_pending.get('line'):
        blocked = True
        modsec_line = modsec_pending['line']
        modsec_pending['line'] = None

    try:
        # アクセスログを保存
        add_access_log(
            log_data['method'],
            log_data['url'],
            log_data['status'],
            log_data['ip'],
            log_data['access_time'],
            blocked
        )

        # ModSecurityアラートがあれば保存
        if blocked and modsec_line:
            conn = db_connect()
            cursor = conn.cursor()
            cursor.execute("SELECT MAX(id) FROM access_log")
            log_id = cursor.fetchone()[0]
            alerts = parse_modsec_alert(modsec_line)
            save_modsec_alerts(conn, log_id, alerts, log_func=log)

        # URL登録・更新
        add_registry_entry(
            log_data['method'],
            log_data['url'],
            log_data['ip'],
            log_data['access_time']
        )

        return True  # 正常に処理完了

    except Error as e:
        log(f"ログ行の処理に失敗: {e}", "DB ERROR")
        return False


# nginxログをリアルタイム監視（10秒ごとに設定も再取得）
def tail_log(log_func=None, process_func=None):
    """
    ログファイルをリアルタイムで監視し、新規行を処理する
    ファイルポジションを維持し、真のtail機能を実装
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    def process_line(line, modsec_pending):
        if process_func:
            return process_func(line, modsec_pending)
        return None

    log_path = LOG_PATH
    empty_read_count = 0
    max_empty_reads = 50  # 連続で空読みしたらファイルを再オープン
    processed_lines = 0  # 処理した行数をカウント
    last_file_size = 0  # 前回のファイルサイズ
    last_position = 0  # 最後に読み込んだファイル位置を記録

    def get_inode(path):
        try:
            return os.stat(path).st_ino
        except OSError:
            return None

    def get_file_size(path):
        try:
            return os.path.getsize(path)
        except OSError:
            return 0

    log(f"ログファイルの監視を開始: {log_path}", "INFO")

    # ファイルの存在確認
    if not os.path.exists(log_path):
        log(f"ログファイルが見つかりません: {log_path}", "ERROR")
        return

    # ファイルの読み取り権限確認
    if not os.access(log_path, os.R_OK):
        log(f"ログファイルの読み取り権限がありません: {log_path}", "ERROR")
        return

    # 初期ファイルサイズを確認
    file_size = get_file_size(log_path)
    last_file_size = file_size
    log(f"初期ログファイルサイズ: {file_size} bytes", "INFO")

    # 初期処理：ファイル末尾10KBから処理開始
    with open(log_path, "r", encoding="utf-8") as f:
        f.seek(0, 2)  # ファイル末尾に移動
        file_size = f.tell()

        if file_size > 10000:  # 10KB以上の場合
            f.seek(max(0, file_size - 10000))  # 末尾10KBから読み込み
            f.readline()  # 途中の行を破棄
            log("大きなログファイルのため、末尾10KBから処理を開始します", "INFO")
        else:
            f.seek(0)  # 小さなファイルは最初から読み込み
            log("ログファイル全体を初���処理します", "INFO")

        # 既存の行を読み込み（初期処理のみ）
        existing_lines = f.readlines()
        log(f"既存ログ行数: {len(existing_lines)}行を処理中...", "INFO")

        modsec_pending = {'line': None}
        for line in existing_lines:
            if line.strip():
                try:
                    process_line(line, modsec_pending)
                    processed_lines += 1
                except Exception as e:
                    log(f"既存ログ行の処理中にエラー: {e}", "ERROR")
                    continue

        log(f"既存ログの処理完了: {processed_lines}行処理", "INFO")
        log("新規ログの監視を開始します...", "INFO")

        # 現在のファイル位置を記録
        last_position = f.tell()
        log(f"初期処理完了。監視開始位置: {last_position} bytes", "INFO")

    last_inode_val = get_inode(log_path)

    # メインの監視ループ（ファイルポジションを維持）
    while True:
        try:
            # 1時間ごとにattack_patterns.jsonのバージョン確認
            periodic_attack_patterns_update()

            # 10秒ごとに設定を再取得
            if int(time.time()) % 10 == 0:
                fetch_settings()

            # ファイルサイズの変化をチェック
            current_file_size = get_file_size(log_path)
            if current_file_size != last_file_size:
                log(f"ファイルサイズ変化を検出: {last_file_size} -> {current_file_size} bytes", "DEBUG")
                last_file_size = current_file_size

                # ファイルサイズが増加した場合のみ新規ログを読み込み
                if current_file_size > last_position:
                    try:
                        with open(log_path, "r", encoding="utf-8") as f:
                            f.seek(last_position)  # 前回の位置から続行
                            new_lines = []

                            # 新規行をすべて読み込み
                            while True:
                                line = f.readline()
                                if not line:
                                    break
                                new_lines.append(line)

                            # 新規行を処理
                            if new_lines:
                                log(f"新規ログ {len(new_lines)}行を検出して処理開始", "DEBUG")

                                success_count = 0
                                parse_fail_count = 0
                                process_fail_count = 0
                                error_count = 0

                                for i, line in enumerate(new_lines):
                                    if line.strip():
                                        try:
                                            result = process_line(line, modsec_pending)
                                            processed_lines += 1

                                            if result:
                                                success_count += 1
                                            elif result is None:
                                                parse_fail_count += 1
                                            else:
                                                process_fail_count += 1

                                        except Exception as e:
                                            error_count += 1
                                            if error_count <= 3:  # 最初の3件のみ詳細表示
                                                log(f"ログ処理中にエラー (行 {processed_lines}): {e}", "ERROR")
                                                log(f"問題のある行: {line[:100]}...", "ERROR")

                                # 処理結果のサマリー
                                if len(new_lines) == 1:
                                    log(f"新規ログ処理完了: 成功={success_count}, パース失敗={parse_fail_count}, 処理失敗={process_fail_count}, エラー={error_count}", "DEBUG")
                                else:
                                    log(f"バッチ処理完了 ({len(new_lines)}行): 成功={success_count}, パース失敗={parse_fail_count}, 処理失敗={process_fail_count}, エラー={error_count}", "INFO")

                            # ファイル位置を更新
                            last_position = f.tell()

                    except Exception as e:
                        log(f"新規ログ読み込み中にエラー: {e}", "ERROR")

            # ログローテーションの検知
            current_inode = get_inode(log_path)
            if current_inode != last_inode_val:
                log("ログファイルのローテーションを検知しました。監視を再開します。", "INFO")
                last_inode_val = current_inode
                last_position = 0  # ローテーション時は最初から読み込み
                last_file_size = 0

            time.sleep(0.5)  # 0.5秒間隔で監視

        except FileNotFoundError:
            log(f"Log file not found: {log_path}", "ERROR")
            time.sleep(5)
        except PermissionError:
            log(f"Permission denied: {log_path}", "ERROR")
            time.sleep(5)
        except UnicodeDecodeError as e:
            log(f"文字エンコーディングエラー: {e}", "ERROR")
            time.sleep(1)
        except OSError as e:
            log(f"tail_log OSError: {e}", "ERROR")
            time.sleep(1)
        except Exception as e:
            log(f"予期しないエラーが発生: {e}", "ERROR")
            time.sleep(1)


# 定期的にattack_patterns.jsonのバージョン確認を行う
def periodic_attack_patterns_update():
    """
    attack_patterns.jsonのバージョン確認を1時間に1回だけ実施する。
    """
    global LAST_ATTACK_PATTERNS_CHECK
    now = time.time()
    if now - LAST_ATTACK_PATTERNS_CHECK > ATTACK_PATTERNS_CHECK_INTERVAL:
        update_attack_patterns_if_needed()
        LAST_ATTACK_PATTERNS_CHECK = now


# メイン関数（初期化 → 設定読込 → ログ監視）
def rescan_attack_types():
    """
    既存のurl_registryテーブルの全エントリに対して攻撃タイプを再スキャンする
    """
    try:
        conn = db_connect()
        cursor = conn.cursor()
        cursor.execute("SELECT id, full_url FROM url_registry")
        rows = cursor.fetchall()

        updated_count = 0
        for entry_id, url in rows:
            # 既存の攻撃タイプを取得
            cursor.execute("SELECT attack_type FROM url_registry WHERE id = %s", (entry_id,))
            result = cursor.fetchone()
            old_type = result[0] if result else "unknown"

            new_type = detect_attack_type(url, ATTACK_PATTERNS_PATH)

            if old_type != new_type:
                cursor.execute(
                    "UPDATE url_registry SET attack_type = %s WHERE id = %s",
                    (new_type, entry_id)
                )
                updated_count += 1
                log(f"Updated URL {url}: {old_type} -> {new_type}", "DEBUG")

        conn.commit()
        log(f"attack_type fields have been updated based on latest patterns. Updated: {updated_count}/{len(rows)} entries", "INFO")

        # 攻撃パターンのテストも実行
        if updated_count > 0:
            test_attack_detection()

    except Error as e:
        log(f"rescan_attack_types failed: {e}", "DB ERROR")


def test_attack_detection():
    """
    攻撃パターン検出機能のテスト（データベース書き込みあり）
    テスト完了後に自動でテストデータをクリーンアップ
    """
    from modules.attack_pattern import run_comprehensive_test

    try:
        conn = db_connect()
        if not conn:
            log("データベース接続に失敗したため、テストをスキップします", "WARN")
            return False

        # 包括的なテストを実行（テーブル表示付き）
        result = run_comprehensive_test(ATTACK_PATTERNS_PATH, conn, log_func=log)

        return result

    except Exception as e:
        log(f"テスト実行中にエラーが発生: {e}", "ERROR")
        # 例外発生時もクリーンアップを実行
        try:
            from modules.attack_pattern import cleanup_test_data
            conn = db_connect()
            if conn:
                log("例外発生時のクリーンアップを実行中...", "INFO")
                cleanup_test_data(conn, log_func=log)
        except:
            pass  # クリーンアップ時のエラーは無視
        return False


def cleanup_test_attack_data():
    """
    テスト用攻撃パターンデータのクリーンアップ
    """
    from modules.attack_pattern import cleanup_test_data

    try:
        conn = db_connect()
        if not conn:
            log("データベース接続に失敗したため、クリーンアップをスキップします", "WARN")
            return False

        result = cleanup_test_data(conn, log_func=log)
        return result

    except Exception as e:
        log(f"テストデータクリーンアップ中にエラーが発生: {e}", "ERROR")
        return False


def update_attack_patterns_if_needed():
    """
    attack_patterns.jsonのバージョンをGitHubで確認し、新しい場合は更新する。
    """
    # attack_pattern.pyの機能を使用
    update_if_needed(ATTACK_PATTERNS_PATH, log_func=log)


def main() -> None:
    """
    メイン関数。
    アプリ情報表示、引数処理、DB初期化、設定取得、ログ監視を行う。
    例外発生時はエラーログを出力し終了する。

    戻り値:
        なし
    """
    try:
        print(f"==== {APP_NAME} ====")
        print(f"Version: {APP_VERSION}")
        print(f"{APP_AUTHOR}")
        print()  # 空行はそのまま

        parser = argparse.ArgumentParser(description='NGINX log monitor to MySQL')
        parser.add_argument('--skip-init-db', action='store_true', help='Skip automatic DB initialization')
        parser.add_argument('--run-test', action='store_true', help='Run attack pattern detection test')
        args = parser.parse_args()

        # テスト実行オプションが指定された場合は、テストのみ実行して終了
        if args.run_test:
            log("手動テスト実行が指定されました", "INFO")

            # DB初期化を実行（テストに必要）
            if not init_db():
                log("Database init failed. Exiting.", "ERROR")
                sys.exit(1)

            # テスト実行
            test_result = test_attack_detection()

            if test_result:
                log("テスト実行完了。正常終了します。", "INFO")
                print("\n" + "="*60)
                print("テスト実行が完了しました。")
                print("詳細な結果は上記のテーブルをご確認ください。")
                print("="*60)
                sys.exit(0)  # 正常終了
            else:
                log("テスト実行に失敗しました。", "ERROR")
                print("\n" + "="*60)
                print("テスト実行に失敗しました。")
                print("ログを確認して問題を修正してください。")
                print("="*60)
                sys.exit(1)  # エラー終了

        # 通常の監視モード実行
        rescan_attack_types()

        if not args.skip_init_db:
            if not init_db():
                log("Database init failed. Exiting.", "ERROR")
                sys.exit(1)
            log("Database schema verified or created.", "INFO")

        fetch_settings()
        log("Starting log monitor...", "INFO")
        tail_log(log_func=log, process_func=process_line)

    except KeyboardInterrupt:
        log("キーボード割り込みを受信しました。プログラムを終了します。", "INFO")
        sys.exit(0)
    except Exception as e:
        # 予期しない例外発生時のエラーハンドリング
        log(f"予期しないエラーが発生しました: {e}", "ERROR")
        sys.exit(1)


# エントリーポイント
if __name__ == "__main__":
    main()
