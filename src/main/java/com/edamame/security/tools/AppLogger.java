package com.edamame.security.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * アプリケーション共通のログ出力ユーティリティクラス
 * <p>
 * タイムスタンプ・レベル付きで標準出力にログを出力します。
 * DEBUGレベルは環境変数NGINX_LOG_DEBUGで制御可能です。
 * </p>
 */
public class AppLogger {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 共通ログ出力メソッド
     * @param msg ログメッセージ
     * @param level ログレベル（INFO, WARN, ERROR, DEBUG, CRITICAL, RECOVERED等）
     */
    public static void log(String msg, String level) {
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(FORMATTER);
        if ("DEBUG".equals(level)) {
            String debugEnabled = System.getenv("NGINX_LOG_DEBUG");
            if (!"true".equalsIgnoreCase(debugEnabled)) {
                return;
            }
        }
        System.out.printf("[%s][%s] %s%n", timestamp, level, msg);
    }

    /**
     * INFOレベルのログ出力
     */
    public static void info(String msg) {
        log(msg, "INFO");
    }

    /**
     * WARNレベルのログ出力
     */
    public static void warn(String msg) {
        log(msg, "WARN");
    }

    /**
     * ERRORレベルのログ出力
     */
    public static void error(String msg) {
        log(msg, "ERROR");
    }

    /**
     * DEBUGレベルのログ出力
     */
    public static void debug(String msg) {
        log(msg, "DEBUG");
    }

    /**
     * CRITICALレベルのログ出力
     */
    public static void critical(String msg) {
        log(msg, "CRITICAL");
    }

    /**
     * RECOVEREDレベルのログ出力
     */
    public static void recovered(String msg) {
        log(msg, "RECOVERED");
    }

    // インスタンス化禁止
    private AppLogger() {
        throw new AssertionError("AppLoggerはstaticユーティリティクラスです");
    }
}

