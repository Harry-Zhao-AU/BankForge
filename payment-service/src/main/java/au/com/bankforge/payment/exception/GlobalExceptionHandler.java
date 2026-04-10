package au.com.bankforge.payment.exception;

import au.com.bankforge.common.statemachine.InvalidStateTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/**
 * Centralised exception handler for payment-service.
 *
 * Returns generic error messages — no internal details leaked to clients (T-1-10).
 * Full stack traces are logged server-side.
 *
 * HTTP mapping:
 *   DuplicateRequestException           → 409 Conflict
 *   TransferFailedException             → 400 Bad Request
 *   InvalidStateTransitionException     → 500 Internal Server Error (programming error)
 *   MethodArgumentNotValidException     → 400 Bad Request (Jakarta validation failure)
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicate(DuplicateRequestException ex) {
        log.warn("Duplicate request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorBody("Duplicate request", ex));
    }

    @ExceptionHandler(TransferFailedException.class)
    public ResponseEntity<Map<String, Object>> handleTransferFailed(TransferFailedException ex) {
        log.error("Transfer failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody("Transfer failed", ex));
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidTransition(InvalidStateTransitionException ex) {
        log.error("Invalid state transition: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody("Internal error", ex));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", message, "timestamp", Instant.now().toString()));
    }

    private Map<String, Object> errorBody(String genericMessage, Exception ex) {
        // Return generic message to client; full details are in the log (T-1-10)
        return Map.of(
                "error", genericMessage + ": " + ex.getMessage(),
                "timestamp", Instant.now().toString()
        );
    }
}
