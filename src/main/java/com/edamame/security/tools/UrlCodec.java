package com.edamame.security.tools;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * URLのエンコード・デコード専用ユーティリティクラス
 * <p>
 * 本クラスはURLの%エンコーディング/デコーディング処理を提供します。
 * Java 21の標準APIを利用し、UTF-8固定で動作します。
 * </p>
 */
public class UrlCodec {
    /**
     * URLをデコードする（%エンコーディング → 通常文字、多重エンコード対応）
     * <p>
     * 多重エンコードされたURLも自動的に必要な回数だけデコードします。
     * 最大5回まで再帰的にデコード、デコード前後で変化がなくなるか、%が含まれなくなるまで繰り返します。
     * </p>
     * @param url エンコードされたURL
     * @return デコードされたURL
     */
    public static String decode(String url) {
        if (url == null) return null;
        String prev = url;
        String decoded = url;
        int maxTries = 5;
        for (int i = 0; i < maxTries; i++) {
            try {
                decoded = URLDecoder.decode(decoded.replace("+", " "), StandardCharsets.UTF_8);
                if (decoded.equals(prev) || !decoded.contains("%")) break;
                prev = decoded;
            } catch (Exception e) {
                return prev;
            }
        }
        return decoded;
    }

    /**
     * 文字列をURLエンコードする（UTF-8固定）
     * @param value エンコード対象文字列
     * @return エンコード済みURL文字列
     */
    public static String encode(String value) {
        if (value == null) return null;
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }

    // インスタンス化禁止
    private UrlCodec() {
        throw new AssertionError("UrlCodecはstaticユーティリティクラスです");
    }
}

