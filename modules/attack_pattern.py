"""
攻撃パターン検出・更新モジュール
attack_patterns.jsonによる攻撃タイプ検出とGitHubからの自動更新
PEP8/日本語コメント/スネークケース
"""
import json
import requests
import os
import re
import mysql.connector
from urllib.parse import unquote


def decode_url(url):
    """
    URLをデコードする（%エンコーディング -> 通常文字）
    :param url: エンコードされたURL
    :return: デコードされたURL
    """
    try:
        # プラス記号をスペースに変換
        decoded = url.replace('+', ' ')
        # URLデコードを最大2回実行（二重エンコーディング対応）
        for _ in range(2):
            decoded = unquote(decoded)
        return decoded
    except (UnicodeDecodeError, ValueError):
        return url  # デコードに失敗した場合は元のURLを返す


def detect_attack_type(url, attack_patterns_path, log_func=None):
    """
    URLから攻撃タイプを検出する（正規表現マッチング）
    URLデコード後の文字列でも検査を行う
    :param url: 検査対象のURL
    :param attack_patterns_path: attack_patterns.jsonのパス
    :param log_func: ログ出力用関数（省略可）
    :return: 攻撃タイプ（文字列、複数の場合はカンマ区切り）
    """
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            patterns = json.load(f)

        detected_attacks = []

        # URLをデコード
        decoded_url = decode_url(url)

        # バージョンキーを除外して攻撃パターンを検査
        for attack_type, pattern in patterns.items():
            if attack_type == "version":  # バージョン情報は除外
                continue

            try:
                # 元のURLとデコード後のURLの両方で正規表現チェック
                if (re.search(pattern, url, re.IGNORECASE) or
                    re.search(pattern, decoded_url, re.IGNORECASE)):
                    detected_attacks.append(attack_type)
            except re.error:
                # 正規表現エラーの場合は単純な文字列マッチングにフォールバック
                if (pattern.lower() in url.lower() or
                    pattern.lower() in decoded_url.lower()):
                    detected_attacks.append(attack_type)

        if detected_attacks:
            return ",".join(detected_attacks)  # 複数検出時はカンマ区切り
        else:
            return "normal"  # 攻撃パターンが見つからない場合は正常

    except (FileNotFoundError, PermissionError):
        return "unknown"  # ファイルが見つからない場合や読み取り権限がない場合
    except json.JSONDecodeError:
        return "unknown"  # JSON解析エラーの場合
    except (IOError, OSError) as e:
        if log_func:
            log_func(f"attack_patterns.jsonの読み込みでI/Oエラー: {e}", "WARN")
        return "unknown"  # ファイルI/Oエラーの場合
    except (UnicodeError, TypeError) as e:
        if log_func:
            log_func(f"attack_patterns.json処理でエラー: {e}", "WARN")
        return "unknown"  # エンコーディングエラーやタイプエラーの場合


def update_if_needed(attack_patterns_path, log_func=None):
    """
    GitHubからattack_patterns.jsonの最新バージョンを確認し、
    新しいバージョンがある場合は更新する
    :param attack_patterns_path: ローカルのattack_patterns.jsonのパス
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    github_url = "https://raw.githubusercontent.com/otofuserver/Edamame-NginxLog-Security-Analyzer/master/attack_patterns.json"

    try:
        # ローカルファイルのバージョン情報を取得
        local_version = ""
        if os.path.exists(attack_patterns_path):
            with open(attack_patterns_path, "r", encoding="utf-8") as f:
                local_data = json.load(f)
                local_version = local_data.get("version", "")

        # GitHubから最新バージョンを取得
        log("GitHubからattack_patterns.jsonの最新バージョンを確認中...", "INFO")
        response = requests.get(github_url, timeout=10)
        response.raise_for_status()

        remote_data = response.json()
        remote_version = remote_data.get("version", "")

        # バージョン比較
        if local_version != remote_version:
            log(f"新しいバージョンが見つかりました: {local_version} -> {remote_version}", "INFO")

            # ローカルファイルを更新
            with open(attack_patterns_path, "w", encoding="utf-8") as f:
                json.dump(remote_data, f, ensure_ascii=False, indent=2)

            log(f"attack_patterns.jsonを更新しました (バージョン: {remote_version})", "INFO")
            return True
        else:
            log(f"attack_patterns.jsonは最新バージョンです (バージョン: {local_version})", "INFO")
            return False

    except requests.RequestException as e:
        log(f"GitHubからのattack_patterns.json取得に失敗: {e}", "WARN")
        return False
    except json.JSONDecodeError as e:
        log(f"attack_patterns.jsonのJSON解析に失敗: {e}", "WARN")
        return False
    except Exception as e:
        log(f"attack_patterns.jsonの更新処理でエラーが発生: {e}", "ERROR")
        return False


def get_current_version(attack_patterns_path):
    """
    現在のattack_patterns.jsonのバージョンを取得
    :param attack_patterns_path: attack_patterns.jsonのパス
    :return: バージョン文字列
    """
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data.get("version", "unknown")
    except (FileNotFoundError, PermissionError):
        return "unknown"  # ファイルが見つからない場合や読み取り権限がない場合
    except json.JSONDecodeError:
        return "unknown"  # JSON解析エラーの場合
    except (IOError, OSError):
        return "unknown"  # ファイルI/Oエラーの場合


def validate_attack_patterns_file(attack_patterns_path):
    """
    attack_patterns.jsonファイルの形式を検証
    :param attack_patterns_path: attack_patterns.jsonのパス
    :return: 検証結果（True/False）
    """
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        # 必須キーの存在確認（version）
        if "version" not in data:
            return False

        # 攻撃パターンの形式確認（versionキー以外は攻撃タイプのパターン）
        pattern_count = 0
        for key, value in data.items():
            if key == "version":
                continue

            # 各攻撃タイプのパターンが文字列かどうか確認
            if not isinstance(value, str):
                return False

            pattern_count += 1

        # 少なくとも1つの攻撃パターンが存在するかチェック
        return pattern_count > 0

    except (FileNotFoundError, PermissionError):
        return False  # ファイルが見つからない場合や読み取り権限がない場合
    except json.JSONDecodeError:
        return False  # JSON解析エラーの場合
    except (IOError, OSError):
        return False  # ファイルI/Oエラーの場合


def test_attack_patterns(attack_patterns_path, log_func=None):
    """
    攻撃パターンのテスト用関数（デバッグ用）
    :param attack_patterns_path: attack_patterns.jsonのパス
    :param log_func: ログ出力用関数（省略可）
    :return: テスト結果
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    # テスト用URL例
    test_urls = [
        "/search?q=<script>alert('XSS')</script>",  # XSS
        "/page?id=' or 1=1--",  # SQLi
        "/file?path=../../../etc/passwd",  # LFI
        "/redirect?url=http://example.com",  # OpenRedirect
        "/api?cmd=ls; whoami",  # CommandInjection
        "/normal/page",  # Normal
    ]

    log("攻撃パターンテストを開始...", "INFO")

    for url in test_urls:
        result = detect_attack_type(url, attack_patterns_path)
        log(f"URL: {url} -> 検出結果: {result}", "INFO")

    log("攻撃パターンテスト完了", "INFO")
    return True


def test_attack_patterns_with_db(attack_patterns_path, db_connection, log_func=None):
    """
    攻撃パターンのテスト用関数（データベースへの書き込みあり）
    テストデータをurl_registryに追加し、descriptionにテスト由来であることを明記
    :param attack_patterns_path: attack_patterns.jsonのパス
    :param db_connection: データベース接続オブジェクト
    :param log_func: ログ出力用関数（省略可）
    :return: テスト結果
    """
    from datetime import datetime

    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    # テスト用URL例とその説明
    test_data = [
        {
            "url": "/test/xss?q=<script>alert('XSS')</script>",
            "method": "GET",
            "description": "【テスト自動追加】XSS攻撃パターンのテスト用URL - <script>タグを含むクエリパラメータ"
        },
        {
            "url": "/test/sqli?id=' or 1=1--",
            "method": "GET",
            "description": "【テスト自動追加】SQLインジェクション攻撃パターンのテスト用URL - OR文とコメントアウト"
        },
        {
            "url": "/test/lfi?path=../../../etc/passwd",
            "method": "GET",
            "description": "【テスト自動追加】ローカルファイルインクルージョン攻撃パターンのテスト用URL - ディレクトリトラバーサル"
        },
        {
            "url": "/test/redirect?url=http://malicious.com",
            "method": "GET",
            "description": "【テスト自動追加】オープンリダイレクト攻撃パターンのテスト用URL - 外部ドメインへのリダイレクト"
        },
        {
            "url": "/test/command?cmd=ls; rm -rf /",
            "method": "POST",
            "description": "【テスト自動追加】コマンドインジェクション攻撃パターンのテスト用URL - セミコロンによるコマンド連結"
        },
        {
            "url": "/test/normal/page",
            "method": "GET",
            "description": "【テスト自動追加】正常なアクセスパターンのテスト用URL - 攻撃パターンを含まない通常のリクエスト"
        }
    ]

    log("攻撃パターンテスト（DB書き込みあり）を開始...", "INFO")

    try:
        cursor = db_connection.cursor()
        current_time = datetime.now()
        added_count = 0

        for test_item in test_data:
            url = test_item["url"]
            method = test_item["method"]
            description = test_item["description"]

            # 攻撃タイプを検出
            attack_type = detect_attack_type(url, attack_patterns_path)

            # 既存のテストデータかチェック
            cursor.execute("SELECT id FROM url_registry WHERE full_url = %s", (url,))
            existing = cursor.fetchone()

            if existing:
                # 既存のテストデータを更新
                cursor.execute(
                    """
                    UPDATE url_registry 
                    SET attack_type = %s, description = %s, updated_at = %s 
                    WHERE full_url = %s
                    """,
                    (attack_type, description, current_time, url)
                )
                log(f"テストデータを更新: {url} -> {attack_type}", "DEBUG")
            else:
                # 新規テストデータを追加
                cursor.execute(
                    """
                    INSERT INTO url_registry (
                        method, full_url, created_at, is_whitelisted, description, 
                        updated_at, attack_type, user_final_threat, user_threat_note
                    )
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (method, url, current_time, False, description,
                     current_time, attack_type, None, None)
                )
                added_count += 1
                log(f"テストデータを追加: {url} -> {attack_type}", "DEBUG")

        db_connection.commit()
        log(f"攻撃パターンテスト完了: {added_count}件の新規テストデータを追加しました", "INFO")
        return True

    except mysql.connector.Error as e:
        log(f"データベース関連のエラーが発生: {e}", "ERROR")
        db_connection.rollback()
        return False
    except (ValueError, TypeError) as e:
        log(f"データ型エラーが発生: {e}", "ERROR")
        db_connection.rollback()
        return False
    except Exception as e:
        log(f"テストデータの書き込み中にエラーが発生: {e}", "ERROR")
        db_connection.rollback()
        return False


def cleanup_test_data(db_connection, log_func=None):
    """
    テスト用データをurl_registryから削除する
    :param db_connection: データベース接続オブジェクト
    :param log_func: ログ出力用関数（省略可）
    :return: 削除結果
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = db_connection.cursor()

        # テスト自動追加のデータを削除
        cursor.execute(
            "DELETE FROM url_registry WHERE description LIKE '%【テスト自動追加】%'"
        )
        deleted_count = cursor.rowcount

        db_connection.commit()
        log(f"テストデータのクリーンアップ完了: {deleted_count}件を削除しました", "INFO")
        return True

    except mysql.connector.Error as e:
        log(f"データベース関連のエラーが発生: {e}", "ERROR")
        db_connection.rollback()
        return False
    except Exception as e:
        log(f"テストデータのクリーンアップ中にエラーが発生: {e}", "ERROR")
        db_connection.rollback()
        return False


def display_test_results_table(db_connection, log_func=None):
    """
    テスト結果をテーブル形式で表示する
    :param db_connection: データベース接続オブジェクト
    :param log_func: ログ出力用関数（省略可）
    :return: 表示結果
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        cursor = db_connection.cursor()

        # テストデータを取得
        cursor.execute("""
            SELECT method, full_url, attack_type, description, created_at
            FROM url_registry 
            WHERE description LIKE '%【テスト自動追加】%'
            ORDER BY created_at DESC
        """)

        results = cursor.fetchall()

        if not results:
            log("テストデータが見つかりませんでした", "WARN")
            return False

        # 攻撃タイプのマッピング辞書を関数スコープで定義
        expected_attacks = {
            "xss": "XSS",
            "sqli": "SQLi",
            "lfi": "LFI",
            "redirect": "OpenRedirect",
            "command": "CommandInjection",
            "normal": "normal"
        }

        # テーブルヘッダー
        print("\n" + "="*120)
        print("攻撃パターン検出テスト結果")
        print("="*120)
        print(f"{'Method':<8} {'URL':<50} {'Attack Type':<20} {'Status':<15} {'Created':<20}")
        print("-"*120)

        # テスト結果の表示
        for row in results:
            method, url, attack_type, description, created_at = row

            # URLを短縮表示（50文字制限）
            display_url = url if len(url) <= 50 else url[:47] + "..."

            # URLから期待される攻撃タイプを推測
            expected_type = "normal"
            for key, value in expected_attacks.items():
                if key in url.lower():
                    expected_type = value
                    break

            # テスト結果の判定
            if expected_type == "normal":
                status = "✓ PASS" if attack_type == "normal" else "✗ FAIL"
            else:
                status = "✓ PASS" if expected_type.lower() in attack_type.lower() else "✗ FAIL"

            print(f"{method:<8} {display_url:<50} {attack_type:<20} {status:<15} {created_at.strftime('%Y-%m-%d %H:%M:%S')}")

        print("-"*120)
        print(f"総テスト件数: {len(results)}件")

        # 成功・失敗の統計
        pass_count = 0
        fail_count = 0

        for row in results:
            method, url, attack_type, description, created_at = row

            # URLから期待される攻撃タイプを推測（既に定義済みのexpected_attacks辞書を使用）
            expected_type = "normal"
            for key, value in expected_attacks.items():
                if key in url.lower():
                    expected_type = value
                    break

            if expected_type == "normal":
                if attack_type == "normal":
                    pass_count += 1
                else:
                    fail_count += 1
            else:
                if expected_type.lower() in attack_type.lower():
                    pass_count += 1
                else:
                    fail_count += 1

        print(f"成功: {pass_count}件, 失敗: {fail_count}件")
        print("="*120 + "\n")

        return True

    except mysql.connector.Error as e:
        log(f"データベース関連のエラーが発生: {e}", "ERROR")
        return False
    except (ValueError, TypeError) as e:
        log(f"データ型エラーが発生: {e}", "ERROR")
        return False
    except Exception as e:
        log(f"テスト結果表示中にエラーが発生: {e}", "ERROR")
        return False


def run_comprehensive_test(attack_patterns_path, db_connection, log_func=None):
    """
    包括的なテスト実行（テーブル表示付き���
    :param attack_patterns_path: attack_patterns.jsonのパス
    :param db_connection: データベース接続オブジェクト
    :param log_func: ログ出力用関数（省略可）
    :return: テスト結果
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    try:
        log("=== 攻撃パターン検出テスト開始 ===", "INFO")

        # 1. 既存のテストデータをクリーンアップ
        log("1. 既存テストデータのクリーンアップ中...", "INFO")
        cleanup_test_data(db_connection, log_func=log_func)

        # 2. テストデータを追加してテスト実行
        log("2. テストデータの追加と攻撃パターン検出テスト実行中...", "INFO")
        test_result = test_attack_patterns_with_db(attack_patterns_path, db_connection, log_func=log_func)

        if not test_result:
            log("テストデータの追加に失敗しました", "ERROR")
            return False

        # 3. テスト結果をテーブル形式で表示
        log("3. テスト結果の表示中...", "INFO")
        display_test_results_table(db_connection, log_func=log_func)

        # 4. テスト完了後の自動クリーンアップ
        log("4. テスト完了後の自動クリーンアップ中...", "INFO")
        cleanup_result = cleanup_test_data(db_connection, log_func=log_func)

        if cleanup_result:
            log("=== 攻撃パターン検出テスト正常完了 ===", "INFO")
        else:
            log("=== 攻撃パターン検出テスト完了（クリーンアップで警告あり） ===", "WARN")

        return True

    except mysql.connector.Error as e:
        log(f"データベース関連のエラーが発生: {e}", "ERROR")
        # エラー時もクリーンアップを試行
        try:
            cleanup_test_data(db_connection, log_func=log_func)
        except (mysql.connector.Error, Exception):
            # クリーンアップ失敗は無視
            pass
        return False
    except (IOError, OSError) as e:
        log(f"ファイルI/Oエラーが発生: {e}", "ERROR")
        return False
    except (ValueError, TypeError) as e:
        log(f"データ型エラーが発生: {e}", "ERROR")
        return False
    except Exception as e:
        log(f"予期しないエラーが発生: {e}", "ERROR")
        # エラー時もクリーンアップを試行
        try:
            cleanup_test_data(db_connection, log_func=log_func)
        except (mysql.connector.Error, Exception):
            # クリーンアップ失敗は無視
            pass
        return False
