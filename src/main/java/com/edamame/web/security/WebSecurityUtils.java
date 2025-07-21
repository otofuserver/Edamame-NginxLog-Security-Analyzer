package com.edamame.web.security;

import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;

/**
 * Webセキュリティユーティリティクラス
 * XSS攻撃などのWebアプリケーション攻撃を防御
 */
public class WebSecurityUtils {

    // XSS攻撃パターン（高精度検知用）
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<iframe[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<object[^>]*>.*?</object>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<embed[^>]*>", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<applet[^>]*>.*?</applet>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onload\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onerror\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onclick\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onmouseover\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onfocus\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("expression\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("eval\\s*\\(", Pattern.CASE_INSENSITIVE),
        Pattern.compile("document\\s*\\.", Pattern.CASE_INSENSITIVE),
        Pattern.compile("window\\s*\\.", Pattern.CASE_INSENSITIVE)
    };

    // 危険な文字のエスケープマップ
    private static final Map<String, String> HTML_ESCAPE_MAP = new HashMap<>();
    static {
        HTML_ESCAPE_MAP.put("&", "&amp;");
        HTML_ESCAPE_MAP.put("<", "&lt;");
        HTML_ESCAPE_MAP.put(">", "&gt;");
        HTML_ESCAPE_MAP.put("\"", "&quot;");
        HTML_ESCAPE_MAP.put("'", "&#39;");
        HTML_ESCAPE_MAP.put("/", "&#47;");
        HTML_ESCAPE_MAP.put("\\", "&#92;");
    }

    /**
     * XSS攻撃を検知
     * @param input 検査対象文字列
     * @return XSS攻撃が検知された場合true
     */
    public static boolean detectXSS(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }

        String decoded = htmlDecode(input);
        String urlDecoded = urlDecode(decoded);
        
        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(urlDecoded).find()) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * HTML���スケープ処理（XSS防御）
     * @param input エスケープ対象文字列
     * @return エスケープ済み文字列
     */
    public static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }

        String result = input;
        for (Map.Entry<String, String> entry : HTML_ESCAPE_MAP.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        
        return result;
    }

    /**
     * HTMLアンエスケープ（検知用）
     * @param input アンエスケープ対象文字列
     * @return アンエスケープ済み文字列
     */
    private static String htmlDecode(String input) {
        if (input == null) {
            return "";
        }

        return input
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#47;", "/")
            .replace("&#92;", "\\")
            .replace("&amp;", "&");
    }

    /**
     * URLデコード（検知用）
     * @param input デコード対象文字列
     * @return デコード済み文字列
     */
    private static String urlDecode(String input) {
        if (input == null) {
            return "";
        }

        try {
            return java.net.URLDecoder.decode(input, "UTF-8");
        } catch (Exception e) {
            return input;
        }
    }

    /**
     * JSONエスケープ処理
     * @param input エスケ��プ対象文字列
     * @return エスケープ済み文字列
     */
    public static String escapeJson(String input) {
        if (input == null) {
            return "null";
        }

        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }

    /**
     * SQLインジェクション攻撃検知
     * @param input 検査対象文字列
     * @return SQLインジェクション攻撃が検知された場合true
     */
    public static boolean detectSqlInjection(String input) {
        if (input == null || input.trim().isEmpty()) {
            return false;
        }

        String lowercase = input.toLowerCase();
        
        // SQLキーワードの検知
        String[] sqlKeywords = {
            "union", "select", "insert", "update", "delete", "drop", "create",
            "alter", "exec", "execute", "sp_", "xp_", "cmdshell", "waitfor"
        };

        for (String keyword : sqlKeywords) {
            if (lowercase.contains(keyword)) {
                return true;
            }
        }

        // SQLインジェクション用特殊文字
        return lowercase.contains("'") && (lowercase.contains("or") || lowercase.contains("and")) ||
               lowercase.contains("--") || lowercase.contains("/*") || lowercase.contains("*/");
    }

    /**
     * CSRFトークン生成
     * @return ランダムなCSRFトークン
     */
    public static String generateCsrfToken() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * セキュアヘッダーを取得
     * @return セキュリティヘッダーのMap
     */
    public static Map<String, String> getSecurityHeaders() {
        Map<String, String> headers = new HashMap<>();
        
        // XSS Protection
        headers.put("X-XSS-Protection", "1; mode=block");
        
        // Content Type Options
        headers.put("X-Content-Type-Options", "nosniff");
        
        // Frame Options
        headers.put("X-Frame-Options", "DENY");
        
        // Content Security Policy
        headers.put("Content-Security-Policy", 
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; " +
            "connect-src 'self'; " +
            "font-src 'self'; " +
            "object-src 'none'; " +
            "frame-ancestors 'none'");

        // Strict Transport Security (HTTPS環境でのみ有効)
        // headers.put("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        return headers;
    }

    /**
     * 入力値をサニタイズ（一般用途）
     * @param input サニタイズ対象文字列
     * @return サニタイズ済み文字列
     */
    public static String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }

        // HTMLエスケープを適用
        String sanitized = escapeHtml(input.trim());

        // 制御文字を除去
        sanitized = sanitized.replaceAll("[\u0000-\u001F\u007F-\u009F]", "");

        return sanitized;
    }

    /**
     * URL表示用サニタイズ（URLの正当な文字は保持）
     * @param url サニタイズ対象URL
     * @return サニタイズ済みURL
     */
    public static String sanitizeUrlForDisplay(String url) {
        if (url == null) {
            return "";
        }

        // 基本的な制御文字のみ除去（URLの特殊文字は保持）
        String sanitized = url.replaceAll("[\u0000-\u001F\u007F-\u009F]", "");

        // HTMLエスケープ（<, >, ", 'のみ）
        sanitized = sanitized
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");

        return sanitized;
    }

    /**
     * ファイル名をサニタイズ（パストラバーサル攻撃対策）
     * @param filename サニタイズ対象ファイル名
     * @return サニタイズ済みファイル名
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null) {
            return "";
        }

        // パストラバーサル攻撃対策
        String sanitized = filename
            .replace("../", "")
            .replace("..\\", "")
            .replace("./", "")
            .replace(".\\", "")
            .replace("/", "")
            .replace("\\", "");

        // 危険な文字を除去
        sanitized = sanitized.replaceAll("[<>:\"|?*]", "");

        // 制御文字を除去
        sanitized = sanitized.replaceAll("[\u0000-\u001F\u007F-\u009F]", "");

        return sanitized.trim();
    }
}