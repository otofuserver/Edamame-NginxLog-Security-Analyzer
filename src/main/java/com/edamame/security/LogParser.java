package com.edamame.security;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * ログパーサークラス
 * nginxログの解析・パース機能を提供
 */
public class LogParser {

    // IPアドレス検証用の正規表現パターン
    private static final Pattern IPV4_PATTERN = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_PATTERN = Pattern.compile("^[a-fA-F0-9:]+$");

    // nginxログの複数形式に対応する正規表現パターン
    private static final Pattern[] LOG_PATTERNS = {
        // syslog形式のnginxエラーログ
        Pattern.compile("^[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+\\S+\\[\\d+\\]:\\s+.*?client:\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+).*?request:\\s+\"([^\"]*)\""),

        // syslog形式のnginxアクセスログ
        Pattern.compile("^[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+\\S+\\[\\d+\\]:\\s+(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"([^\"]*)\"\\s+(\\d+)"),

        // syslog形式のmessage repeated行の後のnginxログ
        Pattern.compile("^[A-Za-z]{3}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+\\S+\\[\\d+\\]:\\s+message repeated \\d+ times?: \\[\\s*(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"([^\"]*)\"\\s+(\\d+)"),

        // Combined形式（最も一般的）
        Pattern.compile("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"([^\"]*)\"\\s+(\\d+)\\s+(\\d+|-)\\s+\"([^\"]*)\"\\s+\"([^\"]*)\""),

        // Common形式
        Pattern.compile("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"([^\"]*)\"\\s+(\\d+)\\s+(\\d+|-)"),

        // 簡易形式
        Pattern.compile("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|[a-fA-F0-9:]+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"(\\S+)\\s+(\\S+)[^\"]*\"\\s+(\\d+)"),

        // 代替パターン（IPv4/IPv6対応）
        Pattern.compile("^(\\S+)\\s+\\S+\\s+\\S+\\s+\\[([^\\]]+)\\]\\s+\"([^\"]*)\"\\s+(\\d+)")
    };

    // エラーログのパターン
    private static final String[] ERROR_PATTERNS = {
        "open()",
        "failed (2: No such file or directory)",
        "failed (13: Permission denied)",
        "failed (20: Not a directory)",
        "No such file or directory",
        "Permission denied",
        "Not a directory",
        "*29501 open()",
        "connect() failed"
    };

    // 月名を数値に変換するマップ
    private static final Map<String, Integer> MONTH_MAP = new HashMap<>();
    static {
        MONTH_MAP.put("Jan", 1);
        MONTH_MAP.put("Feb", 2);
        MONTH_MAP.put("Mar", 3);
        MONTH_MAP.put("Apr", 4);
        MONTH_MAP.put("May", 5);
        MONTH_MAP.put("Jun", 6);
        MONTH_MAP.put("Jul", 7);
        MONTH_MAP.put("Aug", 8);
        MONTH_MAP.put("Sep", 9);
        MONTH_MAP.put("Oct", 10);
        MONTH_MAP.put("Nov", 11);
        MONTH_MAP.put("Dec", 12);
    }

    /**
     * IPアドレスの妥当性をチェック
     * @param ipStr IPアドレス文字列
     * @return 有効なIPアドレスの場合true
     */
    private static boolean isInvalidIp(String ipStr) {
        // IPv4アドレスのチェック
        Matcher ipv4Matcher = IPV4_PATTERN.matcher(ipStr);
        if (ipv4Matcher.matches()) {
            for (int i = 1; i <= 4; i++) {
                int octet = Integer.parseInt(ipv4Matcher.group(i));
                if (octet < 0 || octet > 255) {
                    return true; // 無効な場合true
                }
            }
            return false; // 有効な場合false
        }

        // IPv6アドレスのチェック（簡易版）
        return !(IPV6_PATTERN.matcher(ipStr).matches() &&
                (ipStr.contains("::") || ipStr.chars().filter(ch -> ch == ':').count() >= 2));
    }

    /**
     * ログの一部を安全に切り取って表示用文字列を作成
     * @param line ログ行
     * @param maxLength 最大長
     * @return 切り取られたログ文字列
     */
    private static String truncateLog(String line, int maxLength) {
        return line.substring(0, Math.min(maxLength, line.length())) + "...";
    }

    /**
     * nginxログの1行をパースしてデータを抽出
     * 複数のログ形式に対応し、自動判定を行う
     * @param line ログの1行
     * @param logFunc ログ出力用関数（省略可）
     * @return パース結果のMap（失敗時はnull）
     */
    public static Map<String, Object> parseLogLine(String line, BiConsumer<String, String> logFunc) {
        // ログ出力用のヘルパー関数
        BiConsumer<String, String> log = (logFunc != null) ? logFunc :
            (msg, level) -> System.out.printf("[%s] %s%n", level, msg);

        // 空行や無効な行をスキップ
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        line = line.trim();

        // syslogの「message repeated」行をスキップ
        if (line.contains("message repeated") && line.contains("times:")) {
            log.accept("syslogの重複メッセージをスキップ: " + truncateLog(line, 50), "DEBUG");
            return null;
        }

        // file openエラーなどの処理不要なエラーログをスルー
        if (line.contains("[error]")) {
            for (String errorPattern : ERROR_PATTERNS) {
                if (line.contains(errorPattern)) {
                    log.accept("nginxエラーログをスルー: " + truncateLog(line, 80), "DEBUG");
                    return null;
                }
            }
        }

        // syslogでnginxエラーが含まれる行で、アクセスログではない行もスルー
        if (line.matches(".*\\[error\\].*") && !line.matches(".*\"\\w+ /.*")) {
            log.accept("syslog形式のnginxエラーログをスルー: " + truncateLog(line, 80), "DEBUG");
            return null;
        }

        // 各パターンを試行
        for (int i = 0; i < LOG_PATTERNS.length; i++) {
            Matcher matcher = LOG_PATTERNS[i].matcher(line);
            if (matcher.find()) {
                try {
                    return parseWithPattern(matcher, i, line, log);
                } catch (Exception e) {
                    log.accept("パターン " + i + " での解析中にエラー: " + e.getMessage(), "WARN");
                    // continueを削除（ループの最後のため不要）
                }
            }
        }

        log.accept("解析できないログ形式: " + truncateLog(line, 100), "DEBUG");
        return null;
    }

    /**
     * マッチしたパターンに基づいてログを解析
     * @param matcher マッチした正規表現
     * @param patternIndex パターンのインデックス
     * @param originalLine 元のログ行
     * @param log ログ出力関数
     * @return 解析結果のMap
     */
    private static Map<String, Object> parseWithPattern(Matcher matcher, int patternIndex,
                                                       String originalLine, BiConsumer<String, String> log) {
        Map<String, Object> result = new HashMap<>();
        String ipAddress;
        String method = "GET";
        String url = "/";
        String statusCode = "200";
        LocalDateTime accessTime = LocalDateTime.now();

        switch (patternIndex) {
            case 0: // syslog形式のnginxエラーログ
                ipAddress = matcher.group(1);
                String requestLine = matcher.group(2);

                if (isInvalidIp(ipAddress)) {
                    log.accept("無効なIPアドレスを検出（エラーログ）: '" + ipAddress + "' (行: " +
                             truncateLog(originalLine, 50) + ")", "WARN");
                    return null;
                }

                // リクエスト行からメソッドとURLを抽出
                String[] requestParts = requestLine.split("\\s+");
                if (requestParts.length >= 2) {
                    method = requestParts[0];
                    url = requestParts[1];
                } else {
                    method = "GET";
                    url = requestLine.isEmpty() ? "/" : requestLine;
                }

                statusCode = "404"; // エラーログの場合
                accessTime = parseSyslogTimestamp(originalLine);
                break;

            case 1: // syslog形式のnginxアクセスログ
            case 2: // message repeated形式
                ipAddress = matcher.group(1);
                if (isInvalidIp(ipAddress)) {
                    log.accept("無効なIPアドレスを検出: '" + ipAddress + "'", "WARN");
                    return null;
                }

                String timeStr = matcher.group(2);
                String request = matcher.group(3);
                statusCode = matcher.group(4);

                // リクエスト行を解析
                String[] parts = parseRequestLine(request);
                method = parts[0];
                url = parts[1];

                accessTime = parseNginxTimestamp(timeStr);
                break;

            default: // その他のパターン
                ipAddress = matcher.group(1);
                if (isInvalidIp(ipAddress)) {
                    log.accept("無効なIPアドレスを検出: '" + ipAddress + "'", "WARN");
                    return null;
                }

                if (matcher.groupCount() >= 4) {
                    String timeStr2 = matcher.group(2);
                    String request2 = matcher.group(3);
                    statusCode = matcher.group(4);

                    String[] parts2 = parseRequestLine(request2);
                    method = parts2[0];
                    url = parts2[1];

                    accessTime = parseNginxTimestamp(timeStr2);
                }
                break;
        }

        // URLデコード
        try {
            url = URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.accept("URLデコードに失敗: " + url, "WARN");
        }

        result.put("method", method);
        result.put("full_url", url);
        result.put("status_code", Integer.parseInt(statusCode));
        result.put("ip_address", ipAddress);
        result.put("access_time", accessTime);
        result.put("blocked_by_modsec", false); // 初期値

        return result;
    }

    /**
     * リクエスト行を解析してメソッドとURLを抽出
     * @param requestLine リクエスト行
     * @return [method, url]の配列
     */
    private static String[] parseRequestLine(String requestLine) {
        if (requestLine == null || requestLine.trim().isEmpty()) {
            return new String[]{"GET", "/"};
        }

        String[] parts = requestLine.trim().split("\\s+");
        if (parts.length >= 2) {
            return new String[]{parts[0], parts[1]};
        } else if (parts.length == 1) {
            // メソッドのみの場合
            if (parts[0].matches("(GET|POST|PUT|DELETE|HEAD|OPTIONS|PATCH)")) {
                return new String[]{parts[0], "/"};
            } else {
                return new String[]{"GET", parts[0]};
            }
        }

        return new String[]{"GET", "/"};
    }

    /**
     * syslogのタイムスタンプを解析
     * @param line syslogの行
     * @return LocalDateTime
     */
    private static LocalDateTime parseSyslogTimestamp(String line) {
        Pattern syslogPattern = Pattern.compile("^([A-Za-z]{3})\\s+(\\d{1,2})\\s+(\\d{2}:\\d{2}:\\d{2})");
        Matcher matcher = syslogPattern.matcher(line);

        if (matcher.find()) {
            int currentYear = LocalDateTime.now().getYear();
            String monthStr = matcher.group(1);
            int day = Integer.parseInt(matcher.group(2));
            String timeStr = matcher.group(3);

            Integer month = MONTH_MAP.get(monthStr);
            if (month == null) {
                month = 1;
            }

            String[] timeParts = timeStr.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);
            int second = Integer.parseInt(timeParts[2]);

            return LocalDateTime.of(currentYear, month, day, hour, minute, second);
        }

        return LocalDateTime.now();
    }

    /**
     * nginxのタイムスタンプを解析
     * @param timeStr nginxのタイムスタンプ文字列
     * @return LocalDateTime
     */
    private static LocalDateTime parseNginxTimestamp(String timeStr) {
        try {
            // nginxの標準形式: 10/Jul/2025:02:29:57 +0900
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss");
            // タイムゾーン部分を除去
            String cleanTimeStr = timeStr.replaceAll("\\s+[+-]\\d{4}$", "");
            return LocalDateTime.parse(cleanTimeStr, formatter);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}
