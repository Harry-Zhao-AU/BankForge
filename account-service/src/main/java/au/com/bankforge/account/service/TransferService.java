package au.com.bankforge.account.service;

import au.com.bankforge.account.dto.TransferRequest;
import au.com.bankforge.account.dto.TransferResponse;
import au.com.bankforge.account.entity.Account;
import au.com.bankforge.account.entity.OutboxEvent;
import au.com.bankforge.account.exception.AccountNotFoundException;
import au.com.bankforge.account.exception.InsufficientFundsException;
import au.com.bankforge.account.repository.AccountRepository;
import au.com.bankforge.account.repository.OutboxEventRepository;
import au.com.bankforge.common.dto.TransferCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * ACID fund transfer service.
 *
 * executeTransfer is the critical CORE-02 / TXNS-01 method.
 * It executes debit + credit + outbox write in a SINGLE @Transactional boundary.
 *
 * DEADLOCK PREVENTION:
 *   Always acquire locks in ascending UUID order. Two concurrent transfers between
 *   the same pair of accounts will both try to lock the same account first, so
 *   they serialise rather than deadlock.
 *
 * PESSIMISTIC_WRITE:
 *   findByIdForUpdate uses SELECT FOR UPDATE (D-10). Balance check MUST happen
 *   AFTER lock acquisition to prevent TOCTOU race conditions under Read Committed.
 *
 * OUTBOX PATTERN:
 *   The outbox_event row is written in the same transaction as the balance changes.
 *   If the transaction rolls back (e.g., InsufficientFunds), the outbox row is also
 *   rolled back — no orphan events, no dual-write problem (T-1-03).
 *
 * JACKSON 3:
 *   Uses tools.jackson.databind.ObjectMapper (Jackson 3, bundled with Spring Boot 4.0.5).
 *   Do NOT use com.fasterxml.jackson.databind.ObjectMapper (Jackson 2 — won't compile).
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Executes an atomic fund transfer: debit source, credit target, write outbox row.
     * All three operations occur within a single PostgreSQL transaction (TXNS-01).
     *
     * @throws AccountNotFoundException   if either account does not exist
     * @throws InsufficientFundsException if source account balance < amount
     */
    @Transactional
    public TransferResponse executeTransfer(TransferRequest request) {
        UUID fromId = request.fromAccountId();
        UUID toId   = request.toAccountId();
        BigDecimal amount = request.amount();

        // DEADLOCK PREVENTION: always lock the lower UUID first.
        // compareTo on UUIDs is deterministic — both threads acquire locks in the same order.
        UUID first  = fromId.compareTo(toId) < 0 ? fromId : toId;
        UUID second = fromId.compareTo(toId) < 0 ? toId   : fromId;

        Account lockFirst  = accountRepository.findByIdForUpdate(first)
                .orElseThrow(() -> new AccountNotFoundException(first));
        Account lockSecond = accountRepository.findByIdForUpdate(second)
                .orElseThrow(() -> new AccountNotFoundException(second));

        // Identify debit and credit accounts from the locked pair
        Account debit  = lockFirst.getId().equals(fromId) ? lockFirst  : lockSecond;
        Account credit = lockFirst.getId().equals(fromId) ? lockSecond : lockFirst;

        // Balance check AFTER lock acquisition — not before (TOCTOU prevention per D-10)
        if (debit.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(fromId, amount);
        }

        // Atomic balance update (both persist in same TX via dirty-checking)
        debit.setBalance(debit.getBalance().subtract(amount));
        credit.setBalance(credit.getBalance().add(amount));

        // Outbox write — SAME transaction as balance changes (T-1-03: immutable audit trail)
        UUID transferId = UUID.randomUUID();
        OutboxEvent outbox = OutboxEvent.builder()
                .aggregatetype("Transfer")
                .aggregateid(transferId.toString())
                .type("TransferInitiated")
                .payload(serializePayload(transferId, fromId, toId, amount))
                .build();
        outboxEventRepository.save(outbox);

        return new TransferResponse(transferId, fromId, toId, amount, "COMPLETED", Instant.now());
    }

    private String serializePayload(UUID transferId, UUID fromId, UUID toId, BigDecimal amount) {
        try {
            TransferCreatedEvent event = new TransferCreatedEvent(
                    transferId, fromId, toId, amount, "COMPLETED", Instant.now());
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize outbox payload", e);
        }
    }
}
