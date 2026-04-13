package au.com.bankforge.payment.service;

import au.com.bankforge.common.enums.TransferEvent;
import au.com.bankforge.common.enums.TransferState;
import au.com.bankforge.common.statemachine.TransferStateMachine;
import au.com.bankforge.payment.client.AccountServiceClient;
import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.dto.TransferStatusResponse;
import au.com.bankforge.payment.entity.Transfer;
import au.com.bankforge.payment.repository.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   2. Persist Transfer in PENDING state
 *   3. PENDING → PAYMENT_PROCESSING (via TransferStateMachine)
 *   4. Call account-service for ACID fund transfer execution
 *   5. PAYMENT_PROCESSING → PAYMENT_DONE → POSTING → CONFIRMED (happy path)
 *   6. On exception: current_state → COMPENSATING → CANCELLED (failure path)
 *   7. Cache response in Redis for future idempotent replays
 *
 * IMPORTANT — @Transactional scope:
 *   Covers payment-service DB operations (transfer record state updates) only.
 *   The account-service ACID transfer is a separate transaction in account-service's DB.
 *   This is intentional — each service owns its own DB (project constraint).
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
    private final TransferStateMachine stateMachine;
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
     * Returns cached response without re-executing if the idempotency key already exists
     * in Redis (TXNS-05 duplicate prevention).
     *
     * @param request validated transfer request
     * @return InitiateTransferResponse with transfer ID and final state
     */
    @Transactional
    public InitiateTransferResponse initiateTransfer(InitiateTransferRequest request) {
        return transferDurationTimer.record(() -> {
            // Step 1: Idempotency check (Redis) per TXNS-05 — check BEFORE creating any DB record
            Optional<String> cached = idempotencyService.getCached(request.idempotencyKey());
            if (cached.isPresent()) {
                log.atInfo().addKeyValue("idempotencyKey", request.idempotencyKey()).log("Duplicate request detected");
                return deserialize(cached.get());
            }

            // Step 2: Create transfer record in PENDING state
            Transfer transfer = Transfer.builder()
                    .fromAccountId(request.fromAccountId())
                    .toAccountId(request.toAccountId())
                    .amount(request.amount())
                    .idempotencyKey(request.idempotencyKey())
                    .description(request.description())
                    .state(TransferState.PENDING)
                    .build();
            transfer = transferRepository.save(transfer);
            incrementTransferInitiated(TransferState.PENDING);
            log.atInfo().addKeyValue("transferId", transfer.getId()).log("Transfer initiated");

            try {
                // Step 3: PENDING -> PAYMENT_PROCESSING
                transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.INITIATE));
                transfer = transferRepository.save(transfer);

                // Step 4: Call account-service for ACID transfer execution
                accountServiceClient.executeTransfer(
                        request.fromAccountId(), request.toAccountId(),
                        request.amount(), request.description());

                // Step 5: PAYMENT_PROCESSING -> PAYMENT_DONE
                transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.PAYMENT_COMPLETE));
                transfer = transferRepository.save(transfer);

                // Step 6: PAYMENT_DONE -> POSTING (ledger-service will handle confirmation in Phase 1.1)
                transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.POST));
                transfer = transferRepository.save(transfer);

                // Step 7: POSTING -> CONFIRMED
                // For Phase 1 only: auto-confirms since ledger-service is a stub.
                // Phase 1.1 will make this event-driven via Kafka confirmation event.
                transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.CONFIRM));
                transfer = transferRepository.save(transfer);
                incrementTransferInitiated(TransferState.CONFIRMED);
                transferAmountCounter.increment(request.amount().doubleValue());

            } catch (Exception e) {
                log.error("Transfer {} failed: {}", transfer.getId(), e.getMessage());
                // Compensation path: current_state -> COMPENSATING -> CANCELLED
                // TransferStateMachine supports FAIL from PAYMENT_PROCESSING, PAYMENT_DONE, and POSTING.
                // The catch block correctly compensates regardless of which step threw.
                try {
                    transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.FAIL));
                    transfer = transferRepository.save(transfer);
                    transfer.setState(stateMachine.transition(transfer.getState(), TransferEvent.COMPENSATE));
                    transfer.setErrorMessage(e.getMessage());
                    transfer = transferRepository.save(transfer);
                    incrementTransferInitiated(TransferState.CANCELLED);
                } catch (Exception compensationError) {
                    log.error("Compensation failed for {}: {}", transfer.getId(), compensationError.getMessage());
                    transfer.setErrorMessage("Compensation failed: " + compensationError.getMessage());
                    transfer = transferRepository.save(transfer);
                }
            }

            // Step 8: Cache response in Redis for future idempotent replays (TXNS-05)
            InitiateTransferResponse response = new InitiateTransferResponse(
                    transfer.getId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                    transfer.getAmount(), transfer.getState().name(), transfer.getCreatedAt());
            idempotencyService.setIfNew(request.idempotencyKey(), serialize(response));

            return response;
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
