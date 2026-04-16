package au.com.bankforge.payment.service;

import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.payment.client.AccountServiceClient;
import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.dto.TransferStatusResponse;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.context.Scope;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Payment orchestration service — external entry point for all fund transfers (D-02, CORE-03).
 *
 * Orchestration flow per D-04 and D-06:
 *   1. Redis idempotency check (TXNS-05) — return cached response if duplicate
 *   2. Persist Transfer PENDING → PAYMENT_PROCESSING in an isolated TX (via TransferStateService)
 *   3. Call account-service for ACID fund transfer execution (outside any transaction)
 *   4. On success: PAYMENT_PROCESSING → PAYMENT_DONE → POSTING (new isolated TX); ledger confirms async
 *   5. On failure: best-effort reversal call to account-service, then FAIL → COMPENSATING → CANCELLED
 *   6. Cache response in Redis for future idempotent replays
 *
 * TRANSACTION DESIGN:
 *   initiateTransfer itself is NOT @Transactional. All DB operations are delegated to
 *   TransferStateService methods that use REQUIRES_NEW. This ensures:
 *   - The Transfer record is committed before the HTTP call (no split-brain on DB failure after money moves)
 *   - The cancel() path runs in a fresh transaction and always commits, even if the calling
 *     context was marked rollback-only by a prior RuntimeException
 *
 * IDEMPOTENCY RACE GUARD:
 *   Redis check is the fast path. The DB unique index on idempotency_key (T-1-06) is the
 *   fallback — DataIntegrityViolationException from a concurrent insert is caught and resolved
 *   by looking up the existing record.
 *
 * JACKSON 3:
 *   Uses tools.jackson.databind.ObjectMapper (Jackson 3, bundled with Spring Boot 4.0.5).
 *   Do NOT use com.fasterxml.jackson.databind.ObjectMapper (Jackson 2 — won't compile).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final TransferRepository transferRepository;
    private final TransferStateService transferStateService;
    private final IdempotencyService idempotencyService;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper; // tools.jackson.databind.ObjectMapper (Jackson 3)
    private final MeterRegistry meterRegistry;

    private Counter transferAmountCounter;
    private Counter transferDltCounter;
    private Timer transferDurationTimer;

    @PostConstruct
    void initMetrics() {
        transferAmountCounter = Counter.builder("transfer_amount_total")
            .description("Total AUD amount transferred")
            .tag("service", "payment-service")
            .register(meterRegistry);
        transferDltCounter = Counter.builder("transfer_dlt_messages_total")
            .description("Messages routed to dead letter topic")
            .tag("service", "payment-service")
            .register(meterRegistry);
        transferDurationTimer = Timer.builder("transfer_duration")
            .description("Time taken to process a transfer end-to-end")
            .tag("service", "payment-service")
            .publishPercentileHistogram(true)
            .register(meterRegistry);
    }

    private void incrementTransferInitiated(TransferState state) {
        Counter.builder("transfer_initiated_total")
            .description("Number of transfers initiated")
            .tag("service", "payment-service")
            .tag("state", state.name())
            .register(meterRegistry)
            .increment();
    }

    /**
     * Initiates a fund transfer with idempotency protection.
     *
     * NOT @Transactional — DB operations are delegated to TransferStateService (REQUIRES_NEW).
     * This keeps the account-service HTTP call outside any transaction boundary.
     *
     * @param request validated transfer request
     * @return InitiateTransferResponse with transfer ID and final state
     */
    public InitiateTransferResponse initiateTransfer(InitiateTransferRequest request) {
        return transferDurationTimer.record(() -> {
            // Step 1: Idempotency check (Redis) — fast path before any DB write
            Optional<String> cached = idempotencyService.getCached(request.idempotencyKey());
            if (cached.isPresent()) {
                log.atInfo().addKeyValue("idempotencyKey", request.idempotencyKey()).log("Duplicate request detected");
                return deserialize(cached.get());
            }

            // Step 2: PENDING → PAYMENT_PROCESSING in an isolated committed transaction.
            // Committed before the HTTP call so the record exists even if the call fails.
            // DataIntegrityViolationException means a concurrent request raced past the Redis
            // check with the same idempotency key — resolve by returning the existing record.
            Transfer transfer;
            try {
                transfer = transferStateService.createAndStart(request);
            } catch (DataIntegrityViolationException e) {
                log.atInfo()
                    .addKeyValue("idempotencyKey", request.idempotencyKey())
                    .log("Idempotency race resolved via DB constraint — returning existing record");
                transfer = transferRepository.findByIdempotencyKey(request.idempotencyKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Idempotency constraint fired but record not found for key: " + request.idempotencyKey()));
                return buildAndCacheResponse(transfer, request.idempotencyKey());
            }

            // D-15 Layer 2: Set OTel Baggage so all downstream spans carry the transaction ID.
            // Set after createAndStart() so transfer.getId() is available.
            // Not set on idempotency early-return paths (Redis hit, DataIntegrityViolationException)
            // — those are replays where the original execution already set baggage.
            Baggage baggage = Baggage.current().toBuilder()
                    .put("banking.transaction.id", transfer.getId().toString())
                    .build();
            try (Scope scope = baggage.makeCurrent()) {

                incrementTransferInitiated(TransferState.PENDING);
                log.atInfo().addKeyValue("transferId", transfer.getId()).log("Transfer initiated");

                // Step 3: ACID transfer in account-service — outside any transaction.
                // Track whether the call returned successfully so we know whether to attempt reversal.
                boolean transferExecuted = false;
                try {
                    accountServiceClient.executeTransfer(
                            request.fromAccountId(), request.toAccountId(),
                            request.amount(), request.description());
                    transferExecuted = true;

                    // Step 4: PAYMENT_PROCESSING → PAYMENT_DONE → POSTING (ledger confirms async via Kafka)
                    transfer = transferStateService.advanceToPosting(transfer.getId());
                    incrementTransferInitiated(TransferState.POSTING);
                    transferAmountCounter.increment(request.amount().doubleValue());

                } catch (Exception e) {
                    log.error("Transfer {} failed: {}", transfer.getId(), e.getMessage());

                    // Step 5a: Best-effort reversal — only attempted if executeTransfer() returned
                    // successfully (transferExecuted = true), meaning money definitely moved.
                    // If executeTransfer() itself threw, the outcome is uncertain (network timeout
                    // may mean money did or did not move); in that case we do NOT attempt reversal
                    // to avoid a double-move. An alert/manual review is required for those cases.
                    if (transferExecuted) {
                        try {
                            accountServiceClient.reverseTransfer(
                                    request.fromAccountId(), request.toAccountId(),
                                    request.amount(),
                                    "Reversal of failed transfer " + transfer.getId());
                            log.info("Reversal succeeded for transfer {}", transfer.getId());
                        } catch (Exception reverseEx) {
                            log.error("CRITICAL: Reversal failed for transfer {} — manual intervention required. Cause: {}",
                                    transfer.getId(), reverseEx.getMessage());
                        }
                    }

                    // Step 5b: FAIL → COMPENSATING → CANCELLED in a fresh isolated transaction.
                    // REQUIRES_NEW in cancel() guarantees this commits even if any prior context
                    // was marked rollback-only.
                    transfer = transferStateService.cancel(transfer.getId(), e.getMessage());
                    incrementTransferInitiated(TransferState.CANCELLED);
                }

                // Step 6: Cache response in Redis for future idempotent replays (TXNS-05).
                // Cached regardless of outcome — CANCELLED responses are intentionally cached so
                // retries with the same idempotency key return the same result without re-executing.
                return buildAndCacheResponse(transfer, request.idempotencyKey());
            }
        });
    }

    /**
     * Returns the current state of a transfer.
     *
     * @param transferId transfer UUID
     * @return TransferStatusResponse with current state and any error message
     * @throws RuntimeException if transfer not found
     */
    @Transactional(readOnly = true)
    public TransferStatusResponse getTransferStatus(UUID transferId) {
        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new RuntimeException("Transfer not found: " + transferId));
        return new TransferStatusResponse(
                transfer.getId(), transfer.getState().name(), transfer.getAmount(),
                transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getErrorMessage(), transfer.getCreatedAt(), transfer.getUpdatedAt());
    }

    /**
     * Returns true if no cached response exists for the idempotency key.
     * Used by PaymentController to determine HTTP 201 vs 200 (TXNS-05).
     *
     * @param idempotencyKey client-provided idempotency key
     * @return true if this is a new (unseen) transfer
     */
    public boolean isNewTransfer(String idempotencyKey) {
        return idempotencyService.getCached(idempotencyKey).isEmpty();
    }

    private InitiateTransferResponse buildAndCacheResponse(Transfer transfer, String idempotencyKey) {
        InitiateTransferResponse response = new InitiateTransferResponse(
                transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getAmount(), transfer.getState().name(), transfer.getCreatedAt());
        idempotencyService.setIfNew(idempotencyKey, serialize(response));
        return response;
    }

    private String serialize(InitiateTransferResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    private InitiateTransferResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, InitiateTransferResponse.class);
        } catch (Exception e) {
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
