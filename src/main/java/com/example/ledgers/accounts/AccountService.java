package com.example.ledgers.accounts;

import com.example.ledgers.accounts.dto.CreateAccountRequest;
import com.example.ledgers.accounts.dto.UpdateAccountRequest;
import com.example.ledgers.tenancy.TenantPrincipal;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface AccountService {

    Account createAccount(TenantPrincipal principal, CreateAccountRequest request);

    Account createSystemAccount(TenantPrincipal principal, String path);

    Account getAccount(TenantPrincipal principal, UUID accountId);

    List<Account> listAccounts(TenantPrincipal principal, String prefix, int limit);

    Account updateAccount(TenantPrincipal principal, UUID accountId, UpdateAccountRequest request);

    Map<String, UUID> resolvePaths(UUID tenantId, Collection<String> paths);

    Map<UUID, String> pathsById(UUID tenantId, Collection<UUID> accountIds);

    List<Account> getAccounts(UUID tenantId, Collection<UUID> accountIds);

    List<Account> lockAccounts(UUID tenantId, Collection<UUID> accountIds);

    @Transactional(readOnly = true)
    Set<UUID> findAccountIdsUnder(UUID tenantId, String prefix);
}
