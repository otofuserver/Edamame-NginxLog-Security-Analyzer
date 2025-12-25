package com.edamame.web.exception;

/**
 * リソースが既に存在する場合にスローするランタイム例外
 */
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException() { super(); }
    public DuplicateResourceException(String message) { super(message); }
    public DuplicateResourceException(String message, Throwable cause) { super(message, cause); }
}

