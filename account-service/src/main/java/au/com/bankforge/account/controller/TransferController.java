package au.com.bankforge.account.controller;

import au.com.bankforge.account.dto.TransferRequest;
import au.com.bankforge.account.dto.TransferResponse;
import au.com.bankforge.account.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST controller for ACID fund transfers.
 *
 * /api/internal/transfers is an internal endpoint — called by payment-service, NOT
 * routed externally. In Phase 3, Kong Gateway will NOT expose /api/internal/** paths.
 *
 * This separation prevents external clients from calling transfer directly, enforcing
 * that all external transfers flow through payment-service's idempotency and state machine.
 */
@RestController
@RequestMapping("/api/internal/transfers")
@RequiredArgsConstructor
@Validated
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> executeTransfer(
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.ok(transferService.executeTransfer(request));
    }
}
