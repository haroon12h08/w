package com.wbank.platform.error;

/**
 * Base type for failures that are meaningful in the financial domain.
 *
 * <p>These are not bugs and not transport errors: they are the system correctly
 * refusing to do something. Each carries a stable machine-readable {@code errorCode}
 * so callers (and, later, the intelligence layer) can reason about outcomes without
 * parsing prose.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
