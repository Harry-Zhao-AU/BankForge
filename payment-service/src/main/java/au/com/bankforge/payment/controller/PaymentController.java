package au.com.bankforge.payment.controller;

import au.com.bankforge.payment.dto.InitiateTransferRequest;
import au.com.bankforge.payment.dto.InitiateTransferResponse;
import au.com.bankforge.payment.dto.TransferStatusResponse;
import au.com.bankforge.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * External payment API — entry point for all fund transfer operations (CORE-03, D-02).
 *
 * Endpoints:
 *   POST /api/payments/transfers — Initiate a new transfer (201) or replay idempotent response (200)
 *   GET  /api/payments/transfers/{id} — Get current transfer state
 *
 * HTTP status semantics per TXNS-05:
 *   201 CREATED: new transfer (idempotency key not previously seen)
 *   200 OK:      idempotent replay (response already cached in Redis)
 *
 * CRITICAL: The controller checks isNewTransfer() BEFORE calling initiateTransfer()
 * to capture the idempotency state at the moment the request arrives.
 * The computed `status` variable MUST be used in ResponseEntity.status(status) — NOT hardcoded.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Validated
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * POST /api/payments/transfers
     *
     * Initiates a new transfer or returns cached response for duplicate idempotency key.
     * Returns 201 for new transfers, 200 for idempotent replays (TXNS-05).
     */
    @PostMapping("/transfers")
    public ResponseEntity<InitiateTransferResponse> initiateTransfer(
            @Valid @RequestBody InitiateTransferRequest request) {
        // Check idempotency BEFORE calling service to determine correct HTTP status code
        boolean isNew = paymentService.isNewTransfer(request.idempotencyKey());
        InitiateTransferResponse response = paymentService.initiateTransfer(request);
        // Return 201 for new transfer, 200 for idempotent replay per TXNS-05
        HttpStatus status = isNew ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * GET /api/payments/transfers/{id}
     *
     * Returns the current state of a transfer by ID.
     */
    @GetMapping("/transfers/{id}")
    public ResponseEntity<TransferStatusResponse> getTransferStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(paymentService.getTransferStatus(id));
    }
}
