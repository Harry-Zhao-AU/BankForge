package au.com.bankforge.payment.exception;

/**
 * Thrown when account-service returns an error during transfer execution.
 * GlobalExceptionHandler maps this to HTTP 400 Bad Request.
 * Error details are logged server-side, not leaked to client (T-1-10).
 */
public class TransferFailedException extends RuntimeException {

    public TransferFailedException(String message) {
        super(message);
    }

    public TransferFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
