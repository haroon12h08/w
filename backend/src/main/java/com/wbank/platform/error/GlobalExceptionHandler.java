package com.wbank.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(
            String errorCode,
            String message,
            int status,
            String path,
            Instant timestamp,
            Map<String, Object> details
    ) {}

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex, HttpServletRequest request) {
        log.info("Resource not found: {}", ex.getMessage());
        return buildResponse(ex.errorCode(), ex.getMessage(), HttpStatus.NOT_FOUND, request, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex, HttpServletRequest request) {
        log.warn("Conflict error: {}", ex.getMessage());
        return buildResponse(ex.errorCode(), ex.getMessage(), HttpStatus.CONFLICT, request, null);
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRuleViolation(BusinessRuleViolationException ex, HttpServletRequest request) {
        log.warn("Business rule violation: {}", ex.getMessage());
        return buildResponse(ex.errorCode(), ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY, request, null);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex, HttpServletRequest request) {
        log.warn("Domain exception: {}", ex.getMessage());
        return buildResponse(ex.errorCode(), ex.getMessage(), HttpStatus.BAD_REQUEST, request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.info("Request validation failed: {}", message);
        return buildResponse("request.invalid", message, HttpStatus.BAD_REQUEST, request, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return buildResponse("request.missing_header", "Required header missing: " + ex.getHeaderName(),
                HttpStatus.BAD_REQUEST, request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return buildResponse("request.malformed", "Malformed request body", HttpStatus.BAD_REQUEST, request, null);
    }

    /**
     * A database constraint rejected the write. The application is expected to reject
     * invalid financial state before PostgreSQL has to; reaching this handler means the
     * database backstop did its job, and it is logged loudly because it may reveal an
     * application-level gap.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.error("Database integrity constraint rejected a write on {}: {}", request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return buildResponse("ledger.integrity_violation", "The write violates a ledger integrity constraint",
                HttpStatus.CONFLICT, request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.info("Illegal argument: {}", ex.getMessage());
        return buildResponse("request.invalid_argument", ex.getMessage(), HttpStatus.BAD_REQUEST, request, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.warn("Illegal state: {}", ex.getMessage());
        return buildResponse("request.invalid_state", ex.getMessage(), HttpStatus.CONFLICT, request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error on path {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildResponse("internal.error", "An unexpected internal error occurred", HttpStatus.INTERNAL_SERVER_ERROR, request, null);
    }

    private ResponseEntity<ErrorResponse> buildResponse(String errorCode,
                                                       String message,
                                                       HttpStatus status,
                                                       HttpServletRequest request,
                                                       Map<String, Object> details) {
        ErrorResponse body = new ErrorResponse(
                errorCode,
                message,
                status.value(),
                request.getRequestURI(),
                Instant.now(),
                details != null ? details : Map.of()
        );
        return ResponseEntity.status(status).body(body);
    }
}
