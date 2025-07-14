"""
ログパーサーモジュール
nginxログの解析・パース機能を提供
PEP8/日本語コメント/スネークケース
"""
import re
from datetime import datetime


def is_valid_ip(ip_str):
    """
    IPアドレスの妥当性をチェック
    :param ip_str: IPアドレス文字列
    :return: 有効なIPアドレスの場合True
    """
    # IPv4アドレスのチェック
    ipv4_pattern = r'^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$'
    match = re.match(ipv4_pattern, ip_str)
    if match:
        octets = [int(x) for x in match.groups()]
        return all(0 <= octet <= 255 for octet in octets)

    # IPv6アドレスのチェック（簡易版）
    ipv6_pattern = r'^[a-fA-F0-9:]+$'
    if re.match(ipv6_pattern, ip_str) and '::' in ip_str or ip_str.count(':') >= 2:
        return True

    return False


def parse_log_line(line, log_func=None):
    """
    nginxログの1行をパースしてデータを抽出
    複数のログ形式に対応し、自動判定を行う（syslog形式にも対応）
    syslogの「message repeated」行もスキップ処理を追加
    :param line: ログの1行
    :param log_func: ログ出力用関数（省略可）
    :return: パース結果の辞書（失敗時はNone）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    # 空行や無効な行をスキップ
    if not line or not line.strip():
        return None

    line = line.strip()

    # syslogの「message repeated」行をスキップ
    if "message repeated" in line and "times:" in line:
        log(f"syslogの重複メッセージをスキップ: {line[:50]}...", "DEBUG")
        return None

    # nginxログの複数形式に対応する正規表現パターン
    patterns = [
        # syslog形式のnginxログ（修正版：より厳密にIPアドレスを抽出）
        # Jul 10 02:29:57 otofuserver docker-nginx-LO[1587]: 192.168.10.11 - - [10/Jul/2025:02:29:57 +0900] "POST /epgstation/..." 200
        r'^[A-Za-z]{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}\s+\S+\s+\S+\[\d+\]:\s+(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-fA-F0-9:]+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([^"]*)"\s+(\d+)',

        # syslog形式のmessage repeated行の後のnginxログ
        # Jul 10 02:26:51 otofuserver docker-nginx-LO[1587]: message repeated 2 times: [ 192.168.10.11 - - [10/Jul/2025:02:26:51 +0900] "GET /..." 200
        r'^[A-Za-z]{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2}\s+\S+\s+\S+\[\d+\]:\s+message repeated \d+ times?: \[\s*(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-fA-F0-9:]+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([^"]*)"\s+(\d+)',

        # Combined形式（最も一般的）
        r'^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-fA-F0-9:]+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([^"]*)"\s+(\d+)\s+(\d+|-)\s+"([^"]*)"\s+"([^"]*)"',

        # Common形式
        r'^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-fA-F0-9:]+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([^"]*)"\s+(\d+)\s+(\d+|-)',

        # 簡易形式（HTTPメソッドとURLが分離されている場合）
        r'^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}|[a-fA-F0-9:]+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"(\S+)\s+(\S+)[^"]*"\s+(\d+)',

        # フォールバック：最初の非空白文字列をIPとして扱う（検証付き）
        r'^(\S+)\s+\S+\s+\S+\s+\[([^\]]+)\]\s+"([^"]*)"\s+(\d+)'
    ]

    for i, pattern in enumerate(patterns):
        try:
            match = re.match(pattern, line)
            if match:
                if i <= 1:  # syslog形式の場合（通常とmessage repeated両方）
                    ip_candidate = match.group(1)
                    timestamp_str = match.group(2)
                    request_line = match.group(3)
                    status_code = match.group(4)

                    # IPアドレスの妥当性検証
                    if not is_valid_ip(ip_candidate):
                        log(f"無効なIPアドレスを検出（syslog形式）: '{ip_candidate}' (行: {line[:50]}...)", "WARN")
                        continue

                    # リクエスト行からメソッドとURLを抽出
                    request_parts = request_line.split()
                    if len(request_parts) >= 2:
                        method = request_parts[0]
                        url = request_parts[1]
                    else:
                        method = "GET"
                        url = request_line if request_line else "/"

                else:
                    ip_candidate = match.group(1)
                    timestamp_str = match.group(2)

                    # IPアドレスの妥当性検証
                    if not is_valid_ip(ip_candidate):
                        log(f"無効なIPアドレスを検出: '{ip_candidate}' (行: {line[:50]}...)", "WARN")
                        if i < len(patterns) - 1:  # 最後のパターンでなければ次を試す
                            continue
                        else:
                            # 最後のパターンでも無効な場合は、とりあえず処理を続行
                            log(f"最後のパターンでも無効なIP。処理を続行します: '{ip_candidate}'", "WARN")

                    # HTTPメソッドとURLの抽出（パターンによって処理を分岐）
                    if i < 5:  # Combined/Common/簡易形式
                        request_line = match.group(3)
                        status_code = match.group(4)

                        # リクエスト行からメソッドとURLを抽出
                        request_parts = request_line.split()
                        if len(request_parts) >= 2:
                            method = request_parts[0]
                            url = request_parts[1]
                        else:
                            method = "GET"
                            url = request_line if request_line else "/"
                    elif i == 5:  # 簡易形式（メソッドとURLが分離）
                        method = match.group(3)
                        url = match.group(4)
                        status_code = match.group(5)
                    else:  # フォールバック形式
                        request_line = match.group(3)
                        status_code = match.group(4)

                        # リクエスト行からメソッドとURLを抽出
                        request_parts = request_line.split()
                        if len(request_parts) >= 2:
                            method = request_parts[0]
                            url = request_parts[1]
                        else:
                            method = "GET"
                            url = request_line if request_line else "/"

                ip_address = ip_candidate

                # タイムスタンプをdatetimeオブジェクトに変換
                access_time = parse_timestamp(timestamp_str, log)

                # URLの正規化
                if not url.startswith('/'):
                    url = '/' + url

                result = {
                    'ip': ip_address,
                    'method': method.upper(),
                    'url': url,
                    'status': status_code,
                    'access_time': access_time
                }

                # パース成功時のログ（使用したパターンも表示）
                pattern_name = ["syslog", "syslog-repeated", "combined", "common", "簡易", "フォールバック"][i]
                log(f"ログパース成功({pattern_name}形式): {method} {url} (ステータス: {status_code}, IP: {ip_address})", "DEBUG")
                return result

        except Exception as e:
            # パターンエラーは警告レベルで出力
            log(f"パターン{i+1}でのパース中にエラー: {e}", "WARN")
            continue

    # すべてのパターンで失敗した場合
    log(f"ログ行のパースに失敗しました。行の内容: {line[:100]}...", "WARN")
    return None


def parse_timestamp(timestamp_str, log_func=None):
    """
    タイムスタンプ文字列をdatetimeオブジェクトに変換
    :param timestamp_str: タイムスタンプ文字列
    :param log_func: ログ出力用関数
    :return: datetimeオブジェクト
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    # 複数のタイムスタンプ���式に対応
    timestamp_formats = [
        "%d/%b/%Y:%H:%M:%S",      # 10/Jul/2025:14:30:45
        "%Y-%m-%d %H:%M:%S",      # 2025-07-10 14:30:45
        "%d/%m/%Y:%H:%M:%S",      # 10/07/2025:14:30:45
    ]

    # タイムゾーン情報を除去
    timestamp_basic = timestamp_str.split(' ')[0]

    for fmt in timestamp_formats:
        try:
            return datetime.strptime(timestamp_basic, fmt)
        except ValueError:
            continue

    # すべての形式で失敗した場合
    log(f"タイムスタンプの解析に失敗: {timestamp_str}", "WARN")
    return datetime.now()


def parse_combined_log_line(line, log_func=None):
    """
    nginx combined形式のログをパース
    :param line: ログの1行
    :param log_func: ログ出力用関数（省略可）
    :return: パース結果の辞書（失敗時はNone）
    """
    def log(msg, level="INFO"):
        if log_func:
            log_func(msg, level)
        else:
            print(f"[{level}] {msg}")

    # combined形式の正規表現パターン
    combined_pattern = r'^(\S+) \S+ \S+ \[([^\]]+)\] "(\S+) (\S+)[^"]*" (\d+) (\d+) "([^"]*)" "([^"]*)"'

    try:
        match = re.match(combined_pattern, line.strip())
        if not match:
            return None

        ip_address = match.group(1)
        timestamp_str = match.group(2)
        method = match.group(3)
        url = match.group(4)
        status_code = match.group(5)

        # IPアドレスの妥当性検証
        if not is_valid_ip(ip_address):
            log(f"無効なIPアドレス: {ip_address}", "WARN")
            return None

        # タイムスタンプを解析
        access_time = parse_timestamp(timestamp_str, log)

        return {
            'ip': ip_address,
            'method': method.upper(),
            'url': url,
            'status': status_code,
            'access_time': access_time
        }

    except Exception as e:
        log(f"combined形式のログ解析でエラー: {e}", "ERROR")
        return None


def detect_log_format(line):
    """
    ログ行の形式を検出
    :param line: ログの1行
    :return: ログ形式（'common', 'combined', 'unknown'）
    """
    # combined形式の特徴的なパターン（refererとuser-agentを含む）
    combined_pattern = r'^(\S+) \S+ \S+ \[([^\]]+)\] "[^"]*" \d+ \d+ "[^"]*" "[^"]*"'

    # common形式の特徴的なパターン
    common_pattern = r'^(\S+) \S+ \S+ \[([^\]]+)\] "[^"]*" \d+ \d+'

    if re.match(combined_pattern, line.strip()):
        return 'combined'
    elif re.match(common_pattern, line.strip()):
        return 'common'
    else:
        return 'unknown'


def extract_query_params(url):
    """
    URLからクエリパラメータを抽出
    :param url: URL文字列
    :return: クエリパラメータの辞書
    """
    try:
        if '?' not in url:
            return {}

        query_string = url.split('?', 1)[1]
        params = {}

        for param in query_string.split('&'):
            if '=' in param:
                key, value = param.split('=', 1)
                params[key] = value
            else:
                params[param] = ''

        return params

    except Exception:
        return {}


def normalize_url(url):
    """
    URLを正規化（クエリパラメータの除去、末尾スラッシュの統一等）
    :param url: 元のURL
    :return: 正規化されたURL
    """
    try:
        # クエリパラメータを除去
        if '?' in url:
            url = url.split('?')[0]

        # 末尾のスラッシュを統一
        if url.endswith('/') and len(url) > 1:
            url = url[:-1]

        # 空のURLは'/'に正規化
        if not url:
            url = '/'

        return url

    except Exception:
        return url  # エラー時は元のURLを返す

