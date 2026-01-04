package com.edamame.web.exception;

import java.io.Serial;

/**
 * 最後の admin アカウントを無効化/削除しようとした場合にスローする例外
 */
@SuppressWarnings("unused")
public class AdminRetentionException extends RuntimeException {
    // シリアライズ互換性のための識別子
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * デフォルトコンストラクタ
     * シリアライズ/デシリアライズやフレームワークでの利用のために残すこと
     */
    public AdminRetentionException() { super(); }

    /**
     * 指定したメッセージで例外を作成します
     * @param message 例外メッセージ
     */
    public AdminRetentionException(String message) { super(message); }

    /**
     * 指定したメッセージと原因で例外を作成します
     * @param message 例外メッセージ
     * @param cause 原因となった例外
     */
    public AdminRetentionException(String message, Throwable cause) { super(message, cause); }
}
