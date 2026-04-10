package au.com.bankforge.account.controller;

import au.com.bankforge.account.dto.AccountDto;
import au.com.bankforge.account.dto.CreateAccountRequest;
import au.com.bankforge.account.dto.CreateAccountResponse;
import au.com.bankforge.account.dto.TransferHistoryResponse;
import au.com.bankforge.account.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for account management.
 *
 * Endpoints:
 *   POST   /api/accounts              — create account, returns 201
 *   GET    /api/accounts/{id}         — get account by ID, returns 200
 *   GET    /api/accounts/{id}/transfers — list transfer history, returns 200
 *
 * In Phase 3, Kong Gateway will route external traffic to /api/accounts.
 * The /api/internal/transfers path is handled by TransferController (not routed externally).
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
@Validated
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    public ResponseEntity<CreateAccountResponse> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(accountService.createAccount(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountDto> getAccount(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getAccount(id));
    }

    /**
     * Lists transfer history for an account.
     * Returns all outbox events where this account is the source or target of a transfer.
     * Fulfils CORE-01 requirement for transfer history listing.
     */
    @GetMapping("/{id}/transfers")
    public ResponseEntity<List<TransferHistoryResponse>> getTransferHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(accountService.getTransferHistory(id));
    }
}
