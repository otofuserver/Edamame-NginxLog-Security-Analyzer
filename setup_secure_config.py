#!/usr/bin/env python3
# file: setup_secure_config.py

import json
import os
from cryptography.fernet import Fernet
from pathlib import Path
import getpass

KEY_PATH = Path("./secret.key")
ENC_CONFIG_PATH = Path("./db_config.enc")


def generate_key():
    print("\n[1/4] 🔐 暗号鍵を生成中...")
    KEY_PATH.parent.mkdir(parents=True, exist_ok=True)
    key = Fernet.generate_key()
    with open(KEY_PATH, "wb") as f:
        f.write(key)
    os.chmod(KEY_PATH, 0o600)
    print(f"✅ 鍵を {KEY_PATH} に保存しました。")
    return Fernet(key)


def create_config():
    """
    DB接続情報を環境変数または対話入力で取得し、JSONバイト列で返す
    """
    # [2/4] DB接続情報の入力ステップを表示
    print("\n[2/4] 🔒 DB接続情報の入力")

    def getenv_or_input(env, prompt, is_password=False):
        val = os.environ.get(env)
        if val is not None:
            return val.strip()
        if is_password:
            return getpass.getpass(prompt).strip()
        return input(prompt).strip()

    config = {
        "host": getenv_or_input("TEST_MYSQL_HOST", "MySQLホスト名: "),
        "user": getenv_or_input("TEST_MYSQL_USER", "MySQLユーザー名: "),
        "password": getenv_or_input("TEST_MYSQL_PASS", "MySQLパスワード: ", is_password=True),
        "database": getenv_or_input("TEST_MYSQL_DB", "データベース名: "),
    }
    return json.dumps(config).encode()


def encrypt_and_store_config(fernet, data):
    print("\n[3/4] 🔒 設定を暗号化して保存中...")
    encrypted = fernet.encrypt(data)
    with open(ENC_CONFIG_PATH, "wb") as f:
        f.write(encrypted)
    os.chmod(ENC_CONFIG_PATH, 0o600)
    print(f"✅ 暗号化された設定を {ENC_CONFIG_PATH} に保存しました。")


def main():
    print("\n=== セキュアDB構成ウィザード ===")
    fernet = generate_key()
    config_data = create_config()
    encrypt_and_store_config(fernet, config_data)
    print("\n[4/4] ✅ 完了！DB接続設定は安全に保護されました。")


if __name__ == "__main__":
    main()
