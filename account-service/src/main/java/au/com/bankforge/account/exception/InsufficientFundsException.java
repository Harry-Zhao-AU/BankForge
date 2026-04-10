package au.com.bankforge.account.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a transfer is attempted but the source account has insufficient balance.
 * Causes TransferService to throw before any balance changes are made (checked after lock).
 * GlobalExceptionHandler maps this to HTTP 400 Bad Request.
 */
public class InsufficientFundsException extends RuntimeException {

    private final UUID accountId;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(UUID accountId, BigDecimal requestedAmount) {
        super(String.format("Insufficient funds in account %s for amount %s", accountId, requestedAmount));
        this.accountId = accountId;
        this.requestedAmount = requestedAmount;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }
}
