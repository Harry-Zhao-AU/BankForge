package au.com.bankforge.account.service;

import au.com.bankforge.account.dto.AccountDto;
import au.com.bankforge.account.dto.CreateAccountRequest;
import au.com.bankforge.account.dto.CreateAccountResponse;
import au.com.bankforge.account.dto.TransferHistoryResponse;
import au.com.bankforge.account.entity.Account;
import au.com.bankforge.account.entity.OutboxEvent;
import au.com.bankforge.account.exception.AccountNotFoundException;
import au.com.bankforge.account.mapper.AccountMapper;
import au.com.bankforge.account.repository.AccountRepository;
import au.com.bankforge.account.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Service for account CRUD and transfer history listing.
 *
 * createAccount: creates and persists a new account with the provided BSB, account number,
 * account name, and optional initial balance.
 *
 * getAccount: returns account details by ID; throws AccountNotFoundException if absent.
 *
 * getTransferHistory: returns all outbox events involving this account (as fromAccountId
 * or toAccountId), using a native JSONB query on outbox_event. Phase 1 volume is small;
 * pagination is deferred to Phase 2.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final AccountMapper accountMapper;

    @Transactional
    public CreateAccountResponse createAccount(CreateAccountRequest request) {
        Account account = Account.builder()
                .bsb(request.bsb())
                .accountNumber(request.accountNumber())
                .accountName(request.accountName())
                .balance(request.initialBalance() != null ? request.initialBalance() : BigDecimal.ZERO)
                .currency("AUD")
                .build();
        Account saved = accountRepository.save(account);
        return accountMapper.toCreateResponse(saved);
    }

    @Transactional(readOnly = true)
    public AccountDto getAccount(UUID id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));
        return accountMapper.toDto(account);
    }

    /**
     * Returns transfer history for an account by querying the outbox_event table.
     *
     * The native JSONB query finds all outbox events of aggregatetype='Transfer' where
     * payload->>'fromAccountId' or payload->>'toAccountId' matches this account's ID.
     * This supports the CORE-01 requirement for listing transfer history.
     */
    @Transactional(readOnly = true)
    public List<TransferHistoryResponse> getTransferHistory(UUID accountId) {
        // Verify account exists — throws AccountNotFoundException if absent
        accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        List<OutboxEvent> events = outboxEventRepository
                .findTransfersByAccountId(accountId.toString());
        return events.stream()
                .map(e -> new TransferHistoryResponse(e.getId(), e.getType(), e.getPayload(), e.getCreatedAt()))
                .toList();
    }
}
