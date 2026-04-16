package au.com.bankforge.common.enums;

/**
 * Represents the lifecycle states of a bank transfer.
 *
 * State diagram:
 *   PENDING → PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED
 *                               ↓           ↓          ↓
 *                           COMPENSATING ← ← ← ← ← ← ←
 *                               ↓
 *                           CANCELLED
 *
 *   POSTING → FAILED  (operational timeout, set by HungTransferDetector)
 *
 * POSTING = ledger-service is recording the double-entry bookkeeping entries
 * (debit entry + credit entry). Australian banking terminology.
 */
public enum TransferState {
    PENDING,
    PAYMENT_PROCESSING,
    PAYMENT_DONE,
    POSTING,
    CONFIRMED,
    COMPENSATING,
    CANCELLED,
    // FAILED = operational timeout (set by HungTransferDetector, not via FSM transition)
    FAILED
}
