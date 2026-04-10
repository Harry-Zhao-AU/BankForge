package au.com.bankforge.common.enums;

/**
 * Events that drive state transitions in the transfer state machine.
 *
 * INITIATE         — Client initiates the transfer; moves PENDING → PAYMENT_PROCESSING
 * PROCESS          — Internal: payment processing re-entry (idempotent)
 * PAYMENT_COMPLETE — Payment gateway confirms success; moves PAYMENT_PROCESSING → PAYMENT_DONE
 * POST             — Ledger posting initiated; moves PAYMENT_DONE → POSTING
 * CONFIRM          — Ledger confirms entries recorded; moves POSTING → CONFIRMED
 * FAIL             — Any component reports failure; moves current state → COMPENSATING
 * COMPENSATE       — Compensation complete; moves COMPENSATING → CANCELLED
 */
public enum TransferEvent {
    INITIATE,
    PROCESS,
    PAYMENT_COMPLETE,
    POST,
    CONFIRM,
    FAIL,
    COMPENSATE
}
