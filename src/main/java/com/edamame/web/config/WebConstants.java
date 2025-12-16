package com.edamame.web.config;

/**
 * Web関連の定数クラス
 * Cookie名、パス、セキュリティ属性などの共通定数を管理
 */
public final class WebConstants {

    // Cookie設定
    public static final String SESSION_COOKIE_NAME = "EDAMAME_SESSION";
    public static final String COOKIE_PATH = "/";
    public static final String COOKIE_HTTP_ONLY = "HttpOnly";
    public static final int REMEMBER_ME_MAX_AGE = 30 * 24 * 60 * 60; // 30日間（秒単位）

    // エンドポイント
    public static final String LOGIN_PATH = "/login";


    // リダイレクトURL
    public static final String LOGOUT_SUCCESS_REDIRECT = LOGIN_PATH + "?logout=success";

    /**
     * プライベートコンストラクタ（インスタンス化を防止）
     */
    private WebConstants() {
        throw new UnsupportedOperationException("定数クラスはインスタンス化できません");
    }

    /**
     * セッションCookie値を生成
     * @param sessionId セッションID
     * @param rememberMe ログイン状態維持フラグ
     * @return Cookie値文字列
     */
    public static String createSessionCookieValue(String sessionId, boolean rememberMe) {
        StringBuilder cookieValue = new StringBuilder();
        cookieValue.append(SESSION_COOKIE_NAME).append("=").append(sessionId);
        cookieValue.append("; Path=").append(COOKIE_PATH);
        cookieValue.append("; ").append(COOKIE_HTTP_ONLY);

        if (rememberMe) {
            cookieValue.append("; Max-Age=").append(REMEMBER_ME_MAX_AGE);
        }

        return cookieValue.toString();
    }

    /**
     * セッションCookie削除用の値を生成
     * @return Cookie削除用文字列
     */
    public static String createClearSessionCookieValue() {
        return SESSION_COOKIE_NAME + "=; Path=" + COOKIE_PATH + "; " + COOKIE_HTTP_ONLY + "; Max-Age=0";
    }

    /**
     * Cookie名からセッションIDを抽出
     * @param cookieHeader Cookieヘッダー文字列
     * @return セッションID（見つからない場合はnull）
     */
    public static String extractSessionId(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) {
            return null;
        }

        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String trimmedCookie = cookie.trim();
            if (trimmedCookie.startsWith(SESSION_COOKIE_NAME + "=")) {
                return trimmedCookie.substring((SESSION_COOKIE_NAME + "=").length());
            }
        }
        return null;
    }
}
