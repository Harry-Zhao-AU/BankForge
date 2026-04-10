package au.com.bankforge.account.exception;

import java.util.UUID;

/**
 * Thrown when an account lookup by ID returns no result.
 * GlobalExceptionHandler maps this to HTTP 404 Not Found.
 */
public class AccountNotFoundException extends RuntimeException {

    private final UUID accountId;

    public AccountNotFoundException(UUID accountId) {
        super(String.format("Account not found: %s", accountId));
        this.accountId = accountId;
    }

    public UUID getAccountId() {
        return accountId;
    }
}
