"""
攻撃パターン検出・更新モジュール
attack_patterns.jsonによる攻撃タイプ検出とGitHubからの自動更新
PEP8/日本語コメント/スネークケース
"""
import json
import requests
import os
import time
from datetime import datetime


def detect_attack_type(url, attack_patterns_path):
    """
    URLから攻撃タイプを検出する
    :param url: 検査対象のURL
    :param attack_patterns_path: attack_patterns.jsonのパス
    :return: 攻撃タイプ（文字列）
    """
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            patterns = json.load(f)

        # パターンリストから攻撃タイプを検出
        attack_patterns = patterns.get("patterns", {})

        for attack_type, pattern_list in attack_patterns.items():
            for pattern in pattern_list:
                if pattern.lower() in url.lower():
                    return attack_type

        return "normal"  # 攻撃パターンが見つからない場合は正常

    except Exception:
        return "unknown"  # ファイル読み込みエラー等の場合


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
    except Exception:
        return "unknown"


def validate_attack_patterns_file(attack_patterns_path):
    """
    attack_patterns.jsonファイルの形式を検証
    :param attack_patterns_path: attack_patterns.jsonのパス
    :return: 検証結果（True/False）
    """
    try:
        with open(attack_patterns_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        # 必須キーの存在確認
        required_keys = ["version", "patterns"]
        for key in required_keys:
            if key not in data:
                return False

        # パターンデータの形式確認
        patterns = data["patterns"]
        if not isinstance(patterns, dict):
            return False

        # 各攻撃タイプのパターンリストが配列かどうか確認
        for attack_type, pattern_list in patterns.items():
            if not isinstance(pattern_list, list):
                return False

        return True

    except Exception:
        return False
