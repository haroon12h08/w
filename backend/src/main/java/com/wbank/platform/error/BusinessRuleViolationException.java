package com.wbank.platform.error;

/**
 * The request was well-formed and referred to real entities, but performing it would
 * break a rule of the domain. Maps to HTTP 422.
 *
 * <p>Distinguished from {@link jakarta.validation.ConstraintViolationException}-style
 * syntactic validation (400) on purpose: "you sent nonsense" and "the bank will not do
 * this" are different facts, and a future decisioning layer needs to tell them apart.
 */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String errorCode, String message) {
        super(errorCode, message);
    }
}
