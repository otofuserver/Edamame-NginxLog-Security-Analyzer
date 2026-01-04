package com.edamame.web.exception;

import java.io.Serial;

/**
 * リソースが既に存在する場合にスローするランタイム例外
 */
public class DuplicateResourceException extends RuntimeException {
    // シリアライズ互換性のための識別子
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * デフォルトコンストラクタ
     * シリアライズ/デシリアライズやフレームワークでの利用のために残すこと
     */
    @SuppressWarnings("unused")
    public DuplicateResourceException() { super(); }

    /**
     * 指定したメッセージで例外を作成します
     * @param message 例外メッセージ
     */
    @SuppressWarnings("unused")
    public DuplicateResourceException(String message) { super(message); }

    /**
     * 指定したメッセージと原因で例外を作成します
     * @param message 例外メッセージ
     * @param cause 原因となった例外
     */
    @SuppressWarnings("unused")
    public DuplicateResourceException(String message, Throwable cause) { super(message, cause); }
}
