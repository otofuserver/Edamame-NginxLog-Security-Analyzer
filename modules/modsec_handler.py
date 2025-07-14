"""
ModSecurityハンドラーモジュール
ModSecurityのログ検出・アラート解析・保存機能を提供
PEP8/日本語コメント/スネークケース
"""
import re
from mysql.connector import Error


def detect_modsec_block(line):
    """
    ログ行がModSecurityブロックかどうかを判定
    :param line: ログの1行
    :return: ブロック検出結果（True/False）
    """
    # ModSecurityのブロック行を検出するパターン
    block_patterns = [
        r'ModSecurity: Access denied',
        r'ModSecurity.*blocked',
        r'ModSecurity.*denied'
    ]

    for pattern in block_patterns:
        if re.search(pattern, line, re.IGNORECASE):
            return True

    return False


def parse_modsec_alert(line):
    """
    ModSecurityアラート行からアラート情報を抽出
    :param line: ModSecurityアラート行
    :return: アラート情報のリスト
    """
    alerts = []

    try:
        # ルールID抽出パターン
        rule_id_pattern = r'\[id "(\d+)"\]'
        rule_ids = re.findall(rule_id_pattern, line)

        # メッセージ抽出パターン
        msg_pattern = r'\[msg "([^"]+)"\]'
        messages = re.findall(msg_pattern, line)

        # データ抽出パターン
        data_pattern = r'\[data "([^"]+)"\]'
        data_matches = re.findall(data_pattern, line)

        # 重要度抽出パターン
        severity_pattern = r'\[severity "([^"]+)"\]'
        severity_matches = re.findall(severity_pattern, line)

        # 抽出したデータを組み合わせてアラート情報を作成
        max_count = max(len(rule_ids), len(messages), len(data_matches), len(severity_matches))

        for i in range(max_count):
            alert = {
                'rule_id': rule_ids[i] if i < len(rule_ids) else '',
                'msg': messages[i] if i < len(messages) else '',
                'data': data_matches[i] if i < len(data_matches) else '',
                'severity': severity_matches[i] if i < len(severity_matches) else ''
            }
            alerts.append(alert)

        # アラート情報が抽出できない場合は、基本的な情報のみ保存
        if not alerts:
            alerts.append({
                'rule_id': 'unknown',
                'msg': 'ModSecurity Alert Detected',
                'data': line[:500],  # 最大500文字まで
                'severity': 'unknown'
            })

    except Exception:
        # パースエラー時は基本的な情報のみ保存
        alerts.append({
            'rule_id': 'parse_error',
            'msg': 'Failed to parse ModSecurity alert',
            'data': line[:500],
            'severity': 'unknown'
        })

    return alerts


def save_modsec_alerts(conn, access_log_id, alerts, log_func=None):
    """
    ModSecurityアラートをDBに保存
    :param conn: MySQLコネクション
    :param access_log_id: 関連するaccess_logのID
    :param alerts: アラート情報のリスト
    :param log_func: ログ出力用関数（省略可）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    if not alerts:
        return

    cursor = conn.cursor()

    for alert in alerts:
        try:
            # 重複チェック（同じaccess_log_idとrule_idの組み合わせ）
            cursor.execute("""
                SELECT id FROM modsec_alerts 
                WHERE access_log_id = %s AND rule_id = %s
            """, (access_log_id, alert['rule_id']))

            if cursor.fetchone() is None:
                # 新しいアラートを挿入
                cursor.execute("""
                    INSERT INTO modsec_alerts (access_log_id, rule_id, msg, data, severity)
                    VALUES (%s, %s, %s, %s, %s)
                """, (
                    access_log_id,
                    alert['rule_id'],
                    alert['msg'],
                    alert['data'],
                    alert['severity']
                ))
                log(f"ModSecurityアラートを保存しました (rule_id: {alert['rule_id']})", "INFO")

        except Error as e:
            log(f"ModSecurityアラートの保存に失敗: {e}", "DB ERROR")
        except Exception as e:
            log(f"ModSecurityアラート保存でエラーが発生: {e}", "ERROR")

    try:
        conn.commit()
    except Error as e:
        log(f"ModSecurityアラートのコミットに失敗: {e}", "DB ERROR")


def get_modsec_alerts_by_log_id(conn, access_log_id, log_func=None):
    """
    指定されたaccess_log_idに関連するModSecurityアラートを取得
    :param conn: MySQLコネクション
    :param access_log_id: access_logのID
    :param log_func: ログ出力用関数（省略可）
    :return: アラート情報のリスト
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()
    alerts = []

    try:
        cursor.execute("""
            SELECT rule_id, msg, data, severity
            FROM modsec_alerts
            WHERE access_log_id = %s
            ORDER BY id
        """, (access_log_id,))

        rows = cursor.fetchall()
        for row in rows:
            alerts.append({
                'rule_id': row[0],
                'msg': row[1],
                'data': row[2],
                'severity': row[3]
            })

    except Error as e:
        log(f"ModSecurityアラートの取得に失敗: {e}", "DB ERROR")
    except Exception as e:
        log(f"ModSecurityアラート取得でエラーが発生: {e}", "ERROR")

    return alerts


def analyze_modsec_patterns(conn, log_func=None):
    """
    ModSecurityアラートのパターンを分析
    :param conn: MySQLコネクション
    :param log_func: ログ出力用関数（省略可）
    :return: 分析結果の辞書
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    cursor = conn.cursor()
    analysis = {
        'total_alerts': 0,
        'rule_frequency': {},
        'severity_distribution': {},
        'top_rules': []
    }

    try:
        # 総アラート数
        cursor.execute("SELECT COUNT(*) FROM modsec_alerts")
        analysis['total_alerts'] = cursor.fetchone()[0]

        # ルールID別の頻度
        cursor.execute("""
            SELECT rule_id, COUNT(*) as count
            FROM modsec_alerts
            GROUP BY rule_id
            ORDER BY count DESC
        """)

        for row in cursor.fetchall():
            analysis['rule_frequency'][row[0]] = row[1]

        # 重要度別の分布
        cursor.execute("""
            SELECT severity, COUNT(*) as count
            FROM modsec_alerts
            GROUP BY severity
            ORDER BY count DESC
        """)

        for row in cursor.fetchall():
            analysis['severity_distribution'][row[0]] = row[1]

        # 上位10のルール
        cursor.execute("""
            SELECT rule_id, msg, COUNT(*) as count
            FROM modsec_alerts
            GROUP BY rule_id, msg
            ORDER BY count DESC
            LIMIT 10
        """)

        for row in cursor.fetchall():
            analysis['top_rules'].append({
                'rule_id': row[0],
                'msg': row[1],
                'count': row[2]
            })

    except Error as e:
        log(f"ModSecurityアラートの分析に失敗: {e}", "DB ERROR")
    except Exception as e:
        log(f"ModSecurityアラート分析でエラーが発生: {e}", "ERROR")

    return analysis
