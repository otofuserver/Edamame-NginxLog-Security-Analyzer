package com.edamame.web.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * シンプルなBCryptPasswordEncoderの実装
 * Spring Securityの依存関係なしでパスワードハッシュ化を実現
 */
public class BCryptPasswordEncoder {

    private final SecureRandom random;
    private final int strength;

    /**
     * デフォルトコンストラクタ（強度10）
     */
    public BCryptPasswordEncoder() {
        this(10);
    }

    /**
     * 強度指定コンストラクタ
     * @param strength ハッシュ強度（4-31）
     */
    public BCryptPasswordEncoder(int strength) {
        if (strength < 4 || strength > 31) {
            throw new IllegalArgumentException("強度は4-31の範囲で指定してください");
        }
        this.strength = strength;
        this.random = new SecureRandom();
    }

    /**
     * パスワードをハッシュ化
     * @param rawPassword 平文パスワード
     * @return ハッシュ化されたパスワード
     */
    public String encode(CharSequence rawPassword) {
        if (rawPassword == null) {
            throw new IllegalArgumentException("パスワードはnullにできません");
        }

        String salt = generateSalt();
        return hashPassword(rawPassword.toString(), salt);
    }

    /**
     * パスワードの照合
     * @param rawPassword 平文パスワード
     * @param encodedPassword ハッシュ化されたパスワード
     * @return 一致する場合true
     */
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null) {
            return false;
        }

        try {
            // エンコードされたパスワードからソルトを抽出
            String[] parts = encodedPassword.split("\\$");
            if (parts.length != 4) {
                return false;
            }

            String version = parts[0];
            String strength = parts[1];
            String salt = parts[2];
            String hash = parts[3];

            // 同じソルトで入力パスワードをハッシュ化
            String testHash = hashPasswordWithSalt(rawPassword.toString(), salt);

            // ハッシュを比較
            return constantTimeEquals(hash, testHash);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ソルトを生成
     * @return Base64エンコードされたソルト
     */
    private String generateSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /**
     * パスワードをハッシュ化（ソルト付き）
     * @param password 平文パスワード
     * @param salt ソルト
     * @return フォーマット済みハッシュ文字列
     */
    private String hashPassword(String password, String salt) {
        String hash = hashPasswordWithSalt(password, salt);
        return String.format("$2a$%02d$%s$%s", strength, salt, hash);
    }

    /**
     * パスワードをハッシュ化（ソルト指定）
     * @param password 平文パスワード
     * @param salt ソルト
     * @return ハッシュ値
     */
    private String hashPasswordWithSalt(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // ソルトとパスワードを結合
            String combined = salt + password;

            // 指定回数分ハッシュ化を繰り返し（ストレッチング）
            byte[] hash = combined.getBytes("UTF-8");
            int iterations = (int) Math.pow(2, strength);

            for (int i = 0; i < iterations; i++) {
                digest.reset();
                hash = digest.digest(hash);
            }

            return Base64.getEncoder().encodeToString(hash);

        } catch (Exception e) {
            throw new RuntimeException("パスワードハッシュ化に失敗しました", e);
        }
    }

    /**
     * 定数時間での文字列比較（タイミング攻撃対策）
     * @param a 比較文字列A
     * @param b 比較文字列B
     * @return 一致する場合true
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }

        if (a.length() != b.length()) {
            return false;
        }

        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }

        return result == 0;
    }
}
