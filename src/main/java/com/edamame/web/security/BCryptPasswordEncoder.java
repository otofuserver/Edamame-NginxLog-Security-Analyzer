package com.edamame.web.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

/**
 * BCryptPasswordEncoderの実装
 * 本物のBCryptライブラリを使用したパスワードハッシュ化
 */
public class BCryptPasswordEncoder {

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

        return BCrypt.withDefaults().hashToString(strength, rawPassword.toString().toCharArray());
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
            BCrypt.Result result = BCrypt.verifyer().verify(rawPassword.toString().toCharArray(), encodedPassword);
            return result.verified;
        } catch (Exception e) {
            return false;
        }
    }
}
