package au.com.bankforge.payment.service;

import au.com.bankforge.common.enums.TransferEvent;
import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.common.statemachine.TransferStateMachine;
import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Manages Transfer state-machine transitions in isolated transactions.
 *
 * Each method uses REQUIRES_NEW so:
 *   1. createAndStart() commits PENDING → PAYMENT_PROCESSING before the account-service HTTP call.
 *   2. complete() and cancel() run in fresh transactions, independent of any failed outer context.
 *
 * Root cause this solves: previously @Transactional on PaymentService.initiateTransfer wrapped
 * the account-service HTTP call. Any DB write failure after money moved would roll back the
 * payment-service record (split-brain). Additionally, when a RuntimeException propagated from the
 * HTTP call, Spring marked the transaction rollback-only, causing all catch-block saves to fail
 * silently — meaning COMPENSATING and CANCELLED states were never actually persisted.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferStateService {

    private final TransferRepository transferRepository;
    private final TransferStateMachine stateMachine;

    /**
     * Persists a new Transfer (PENDING) and immediately advances to PAYMENT_PROCESSING.
     * Committed to DB before the account-service HTTP call so the record survives any
     * subsequent failure in the calling context.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer createAndStart(InitiateTransferRequest request) {
        Transfer transfer = Transfer.builder()
                .fromAccountId(request.fromAccountId())
                .toAccountId(request.toAccountId())
                .amount(request.amount())
                .idempotencyKey(request.idempotencyKey())
                .description(request.description())
                .state(TransferState.PENDING)
                .build();
        transfer = transferRepository.save(transfer);
        transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.INITIATE));
        return transferRepository.save(transfer);
    }

    /**
     * Advances a transfer: PAYMENT_PROCESSING → PAYMENT_DONE → POSTING.
     * Runs in a new transaction independent of the caller's context.
     * POSTING → CONFIRMED is driven asynchronously by TransferConfirmationListener
     * when ledger-service publishes banking.transfer.confirmed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer advanceToPosting(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.PAYMENT_COMPLETE));
        transfer = transferRepository.save(transfer);
        transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.POST));
        return transferRepository.save(transfer);
    }

    /**
     * Advances a transfer: POSTING → CONFIRMED.
     * Called by TransferConfirmationListener when ledger-service publishes banking.transfer.confirmed.
     * Runs in REQUIRES_NEW so it always commits in a fresh transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer confirm(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.CONFIRM));
        return transferRepository.save(transfer);
    }

    /**
     * Advances a transfer: current state → COMPENSATING → CANCELLED, recording the error message.
     *
     * Uses REQUIRES_NEW so this always runs in a clean transaction. The calling context may already
     * be marked rollback-only after a RuntimeException, which would silently swallow the save.
     * REQUIRES_NEW guarantees the CANCELLED record commits regardless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transfer cancel(UUID transferId, String errorMessage) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        try {
            transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.FAIL));
            transfer = transferRepository.save(transfer);
            transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.COMPENSATE));
            transfer.setErrorMessage(errorMessage);
            return transferRepository.save(transfer);
        } catch (Exception e) {
            log.error("Compensation state transition failed for {}: {}", transferId, e.getMessage());
            transfer.setErrorMessage("Compensation failed: " + e.getMessage());
            return transferRepository.save(transfer);
        }
    }
}
