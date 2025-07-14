"""
アプリケーション設定モジュール
設定値・定数を集約
PEP8/日本語コメント/スネークケース
"""

# アプリケーション情報
APP_NAME = "Edamame NginxLog Security Analyzer"
APP_VERSION = "v1.0.30"
APP_AUTHOR = "Developed by Code Copilot"

# パス設定
LOG_PATH = "/var/log/nginx/nginx.log"
SECURE_CONFIG_PATH = "/run/secrets/db_config_enc"
KEY_PATH = "/run/secrets/db_secret_key"
ATTACK_PATTERNS_PATH = "/run/secrets/attack_patterns.json"

# DB設定
MAX_RETRIES = 5  # DB再接続の最大試行回数
RETRY_DELAY = 3  # DB再接続の待機時間（秒）

# バージョンチェック設定
ATTACK_PATTERNS_CHECK_INTERVAL = 3600  # attack_patterns.jsonのバージョン確認間隔（秒）
