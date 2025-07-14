"""
modulesパッケージの初期化ファイル
各モジュールの機能を統合し、パッケージとして利用可能にします。
PEP8/日本語コメント/スネークケース
"""

# バージョン情報
__version__ = "1.0.0"
__author__ = "Developed by Code Copilot"

# 各モジュールの主要機能をインポート
from .config import (
    APP_NAME, APP_VERSION, APP_AUTHOR, LOG_PATH, SECURE_CONFIG_PATH,
    KEY_PATH, ATTACK_PATTERNS_PATH, MAX_RETRIES, RETRY_DELAY,
    ATTACK_PATTERNS_CHECK_INTERVAL
)

from .db_schema import (
    create_initial_tables, ensure_all_required_columns,
    drop_deprecated_settings_columns, update_settings_version
)

from .log_parser import parse_log_line, parse_combined_log_line

from .modsec_handler import (
    detect_modsec_block, parse_modsec_alert, save_modsec_alerts,
    get_modsec_alerts_by_log_id, analyze_modsec_patterns
)

from .attack_pattern import (
    detect_attack_type, update_if_needed, get_current_version,
    validate_attack_patterns_file
)

# パッケージ情報を外部に公開
__all__ = [
    # config
    'APP_NAME', 'APP_VERSION', 'APP_AUTHOR', 'LOG_PATH', 'SECURE_CONFIG_PATH',
    'KEY_PATH', 'ATTACK_PATTERNS_PATH', 'MAX_RETRIES', 'RETRY_DELAY',
    'ATTACK_PATTERNS_CHECK_INTERVAL',

    # db_schema
    'create_initial_tables', 'ensure_all_required_columns',
    'drop_deprecated_settings_columns', 'update_settings_version',

    # log_parser
    'parse_log_line', 'parse_combined_log_line',

    # modsec_handler
    'detect_modsec_block', 'parse_modsec_alert', 'save_modsec_alerts',
    'get_modsec_alerts_by_log_id', 'analyze_modsec_patterns',

    # attack_pattern
    'detect_attack_type', 'update_if_needed', 'get_current_version',
    'validate_attack_patterns_file'
]
