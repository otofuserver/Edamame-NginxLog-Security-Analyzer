# setup_secure_config.pyで生成した暗号化設定ファイルの内容検証スクリプト
# PEP8・スネークケース・日本語コメント準拠

import json
import sys
from cryptography.fernet import Fernet

def main():
    """
    secret.keyとdb_config.encを復号し、内容が想定通りか検証する
    """
    try:
        with open('secret.key', 'rb') as kf:
            key = kf.read()
        fernet = Fernet(key)
        with open('db_config.enc', 'rb') as ef:
            dec = fernet.decrypt(ef.read())
        conf = json.loads(dec)

        assert conf['host'] == "testhost"
        assert conf['user'] == "testuser"
        assert conf['password'] == "testpass"
        assert conf['database'] == "testdb"
        print("✅ setup_secure_config.py test passed")

    except Exception as e:
        print("❌ setup_secure_config.py test failed:", str(e))
        sys.exit(1)

if __name__ == "__main__":
    main()

