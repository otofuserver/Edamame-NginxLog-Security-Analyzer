package com.edamame.web.security;

import java.util.regex.Pattern;

/**
 * Webセキュリティユーティリティクラス
 * XSS対策、HTMLエスケープ、セキュリティチェック機能を提供
 */
public class WebSecurityUtils {

    // XSS攻撃パターン
    private static final Pattern[] XSS_PATTERNS = {
        Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<iframe[^>]*>.*?</iframe>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<object[^>]*>.*?</object>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("<embed[^>]*>.*?</embed>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
        Pattern.compile("javascript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("vbscript:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onload\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onerror\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onclick\\s*=", Pattern.CASE_INSENSITIVE),
        Pattern.compile("onmouseover\\s*=", Pattern.CASE_INSENSITIVE)
    };

    /**
     * XSS攻撃を検知
     * @param input 検査対象文字列
     * @return XSS攻撃パターンが検出された場合true
     */
    public static boolean detectXSS(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        for (Pattern pattern : XSS_PATTERNS) {
            if (pattern.matcher(input).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * HTMLエスケープ処理
     * @param input エスケープ対象文字列
     * @return エスケープ済み文字列
     */
    public static String escapeHtml(String input) {
        if (input == null) {
            return "";
        }

        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("/", "&#x2F;");
    }

    /**
     * SQLインジェクション攻撃を検知
     * @param input 検査対象文字列
     * @return SQLインジェクション攻撃パターンが検出された場合true
     */
    public static boolean detectSQLInjection(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        String lowerInput = input.toLowerCase();

        // 基本的なSQLインジェクションパターン
        String[] sqlPatterns = {
            "' or '1'='1",
            "' or 1=1",
            "' union select",
            "' drop table",
            "' delete from",
            "' insert into",
            "' update set",
            "' exec ",
            "' execute ",
            "--",
            "/*",
            "*/"
        };

        for (String pattern : sqlPatterns) {
            if (lowerInput.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * パストラバーサル攻撃を検知
     * @param input 検査対象文字列
     * @return パストラバーサル攻撃パターンが検出された場合true
     */
    public static boolean detectPathTraversal(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        String[] pathPatterns = {
            "../",
            "..\\",
            "..%2f",
            "..%5c",
            "%2e%2e%2f",
            "%2e%2e%5c"
        };

        String lowerInput = input.toLowerCase();
        for (String pattern : pathPatterns) {
            if (lowerInput.contains(pattern)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 安全な文字列かチェック（英数字とハイフン、アンダースコアのみ）
     * @param input 検査対象文字列
     * @return 安全な文字列の場合true
     */
    public static boolean isSafeString(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        return input.matches("^[a-zA-Z0-9_-]+$");
    }

    /**
     * セッションIDの形式を検証
     * @param sessionId セッションID
     * @return 有効な形式の場合true
     */
    public static boolean isValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }

        // UUID形式のセッションIDを想定
        return sessionId.matches("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$");
    }

    /**
     * CSRFトークンを生成
     * @return CSRFトークン
     */
    public static String generateCSRFToken() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 入力文字列をサニタイズ（危険な文字を除去・置換）
     * @param input サニタイズ対象文字列
     * @return サニタイズ済み文字列
     */
    public static String sanitizeInput(String input) {
        if (input == null) {
            return "";
        }
        
        return input
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
            .replace("&", "&amp;")
            .replace("/", "&#x2F;")
            .replace("\\", "&#x5C;")
            .replaceAll("[\\r\\n\\t]", " ")
            .trim();
    }

    /**
     * ファイル名をサニタイズ（パストラバーサル対策）
     * @param filename ファイル名
     * @return サニタイズ済みファイル名
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        
        return filename
            .replaceAll("[.][.]", "")
            .replaceAll("[/\\\\]", "")
            .replaceAll("[<>:\"|?*]", "")
            .trim();
    }

    /**
     * URL表示用サニタイズ（表示目的で安全化）
     * - HTMLエスケープは行うが、URLの可読性を損なう「/」のエスケープは除去して表示する。
     * @param url URL文字列
     * @return サニタイズ済みURL（表示用）
     */
    public static String sanitizeUrlForDisplay(String url) {
        if (url == null) {
            return "";
        }

        // 長すぎるURLは切り詰める
        String trimmed = url;
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 197) + "...";
        }

        // 標準の HTML エスケープを行うが、スラッシュは可読性のため復元する
        String escaped = escapeHtml(trimmed);
        // escapeHtml は '/' を '&#x2F;' に置換しているため、表示目的では元に戻す
        escaped = escaped.replace("&#x2F;", "/");
        return escaped;
    }

    /**
     * JSON用エスケープ処理
     * @param input エスケープ対象文字列
     * @return エスケープ済み文字列
     */
    public static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    /**
     * セキュリティヘッダーを取得
     * @return セキュリティヘッダーのMap
     */
    public static java.util.Map<String, String> getSecurityHeaders() {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Frame-Options", "DENY");
        headers.put("X-XSS-Protection", "1; mode=block");
        headers.put("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.put("Content-Security-Policy", 
            "default-src 'self'; " +
            "script-src 'self' 'unsafe-inline'; " +
            "style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data:; " +
            "font-src 'self'; " +
            "connect-src 'self'");
        
        return headers;
    }

    /**
     * クエリ文字列をパースして Map にするユーティリティ
     * @param query クエリ部分（例: "q=alice&page=1"）
     * @return key->value の Map
     */
    public static java.util.Map<String, String> parseQueryParams(String query) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (query == null || query.isEmpty()) return map;
        String[] parts = query.split("&");
        for (String p : parts) {
            int idx = p.indexOf('=');
            if (idx > 0) {
                String k = p.substring(0, idx);
                String v = p.substring(idx + 1);
                try {
                    k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8.name());
                    v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8.name());
                } catch (Exception ignored) {}
                map.put(k, v);
            } else {
                try { map.put(java.net.URLDecoder.decode(p, java.nio.charset.StandardCharsets.UTF_8.name()), ""); } catch (Exception ignored) {}
            }
        }
        return map;
    }
}