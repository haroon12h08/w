package com.wbank.platform.error;

/**
 * The request cannot be applied to the current state of the system: a uniqueness
 * clash, a concurrent modification, or an idempotency key reused with a different
 * payload. Maps to HTTP 409.
 */
public class ConflictException extends DomainException {

    public ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }

    public ConflictException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
