package com.example.ledgers.accounts;

import com.example.ledgers.accounts.dto.CreateAccountRequest;
import com.example.ledgers.accounts.dto.UpdateAccountRequest;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.shared.LedgerSession;
import com.example.ledgers.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private static final int MAX_LIST = 500;

    private final AccountRepository accountRepository;
    private final LedgerSession ledgerSession;

    @Override
    @Transactional
    public Account createAccount(TenantPrincipal principal, CreateAccountRequest request) {
        ledgerSession.bindActor(principal.actor());
        return accountRepository.saveAndFlush(new Account()
                .setTenantId(principal.tenantId())
                .setPath(request.getPath())
                .setAllowNegative(request.isAllowNegative())
                .setMetadata(request.getMetadata() == null ? Map.of() : request.getMetadata()));
    }

    @Override
    @Transactional(readOnly = true)
    public Account getAccount(TenantPrincipal principal, UUID accountId) {
        return findAccount(principal.tenantId(), accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> listAccounts(TenantPrincipal principal, String prefix, int limit) {
        return accountRepository.findAllByTenantIdAndPathStartingWithOrderByPath(principal.tenantId(),
                prefix == null ? "" : prefix, Limit.of(Math.clamp(limit, 1, MAX_LIST)));
    }

    @Override
    @Transactional
    public Account updateAccount(TenantPrincipal principal, UUID accountId, UpdateAccountRequest request) {
        ledgerSession.bindActor(principal.actor());
        Account account = findAccount(principal.tenantId(), accountId);
        if (request.getPath() != null) {
            account.setPath(request.getPath());
        }
        if (request.getAllowNegative() != null) {
            account.setAllowNegative(request.getAllowNegative());
        }
        if (request.getMetadata() != null) {
            account.setMetadata(request.getMetadata());
        }
        return accountRepository.saveAndFlush(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, UUID> resolvePaths(UUID tenantId, Collection<String> paths) {
        Map<String, UUID> result = new HashMap<>();
        List<Account> accounts = accountRepository.findAllByTenantIdAndPathIn(tenantId, paths);
        for (Account account : accounts) {
            result.put(account.getPath(), account.getId());
        }
        if (result.size() != paths.size()) {
            for (String path : paths) {
                if (!result.containsKey(path)) {
                    Optional<String> renamedTo = accountRepository.findCurrentPathOfRetired(tenantId, path);
                    if (renamedTo.isPresent()) {
                        throw ApiException.unprocessable("ERR_ACCOUNT_RENAMED",
                                "Account " + path + " was renamed to " + renamedTo.get());
                    } else {
                        throw ApiException.unprocessable("ERR_ACCOUNT_NOT_FOUND", "No account " + path);
                    }
                }
            }
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> pathsById(UUID tenantId, Collection<UUID> accountIds) {
        return accountRepository.findAllByTenantIdAndIdIn(tenantId, accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getPath));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> getAccounts(UUID tenantId, Collection<UUID> accountIds) {
        return accountRepository.findAllByTenantIdAndIdIn(tenantId, accountIds);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Account> lockAccounts(UUID tenantId, Collection<UUID> accountIds) {
        return accountRepository.lockAccounts(tenantId, accountIds);
    }

    private Account findAccount(UUID tenantId, UUID accountId) {
        return accountRepository.findByIdAndTenantId(accountId, tenantId)
                .orElseThrow(() -> ApiException.notFound("ERR_ACCOUNT_NOT_FOUND", "No account " + accountId));
    }

    @Transactional(readOnly = true)
    @Override
    public Set<UUID> findAccountIdsUnder(UUID tenantId, String prefix) {
        Set<UUID> accountIds = new HashSet<>();
        for (AccountRepository.AccountId account : accountRepository.findAllByTenantIdAndPathStartingWith(tenantId, prefix)) {
            accountIds.add(account.getId());
        }
        return accountIds;
    }
}
