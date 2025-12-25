package com.edamame.web.exception;

/**
 * 最後の admin アカウントを無効化/削除しようとした場合にスローする例外
 */
public class AdminRetentionException extends RuntimeException {
    public AdminRetentionException() { super(); }
    public AdminRetentionException(String message) { super(message); }
    public AdminRetentionException(String message, Throwable cause) { super(message, cause); }
}

