package com.edamame.security.config;

import com.edamame.security.tools.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * アプリケーションのバージョン情報を一元管理するクラス。
 * Gradleのproject.versionで生成されたversion.propertiesから読み込む。
 */
public final class VersionProvider {

    private static final String VERSION_RESOURCE = "/version.properties";
    private static final String VERSION_KEY = "app.version";
    private static final String UNKNOWN = "unknown";
    private static volatile String cachedVersion;

    private VersionProvider() {
    }

    /**
     * バージョン文字列を取得する。
     * @return バージョン（例: 1.0.1）。取得できない場合は"unknown"
     */
    public static String getVersion() {
        String value = cachedVersion;
        if (value != null) {
            return value;
        }
        synchronized (VersionProvider.class) {
            if (cachedVersion != null) {
                return cachedVersion;
            }
            cachedVersion = loadVersion();
            return cachedVersion;
        }
    }

    /**
     * 表示用のバージョン文字列を取得する。
     * @return 先頭に"v"を付けた表示用バージョン（例: v1.0.1）
     */
    public static String getDisplayVersion() {
        return "v" + getVersion();
    }

    private static String loadVersion() {
        try (InputStream is = VersionProvider.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (is == null) {
                AppLogger.log("version.properties が見つかりません。", "WARN");
                return UNKNOWN;
            }
            Properties props = new Properties();
            props.load(is);
            String value = props.getProperty(VERSION_KEY);
            if (value == null || value.isBlank()) {
                AppLogger.log("app.version が version.properties に設定されていません。", "WARN");
                return UNKNOWN;
            }
            return value.trim();
        } catch (IOException e) {
            AppLogger.log("バージョン情報の読み込みに失敗: " + e.getMessage(), "WARN");
            return UNKNOWN;
        }
    }
}
