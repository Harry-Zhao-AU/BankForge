package au.com.bankforge.payment.exception;

/**
 * Thrown when a request is detected as a duplicate via idempotency key.
 * GlobalExceptionHandler maps this to HTTP 409 Conflict.
 */
public class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("Duplicate request detected for idempotency key: " + idempotencyKey);
    }
}
