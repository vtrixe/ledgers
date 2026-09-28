package com.example.ledgers.accounts;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Account> findAllByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    List<Account> findAllByTenantIdAndPathStartingWithOrderByPath(UUID tenantId, String prefix, Limit limit);

    List<Account> findAllByTenantIdAndPathIn(UUID tenantId, Collection<String> paths);

    @Query(value = """
            SELECT a.path FROM account_paths p JOIN accounts a ON a.id = p.account_id
            WHERE p.tenant_id = :tenantId AND p.path = :path""", nativeQuery = true)
    Optional<String> findCurrentPathOfRetired(UUID tenantId, String path);

    /**
     * Row-locks the accounts until the surrounding transaction ends. ORDER BY id: every transaction locks in the same
     * order, so two postings can never deadlock. NO KEY UPDATE (not UPDATE): postings that merely reference these
     * accounts (foreign-key checks) are not blocked.
     */
    @Query(value = """
            SELECT * FROM accounts
            WHERE tenant_id = :tenantId AND id IN (:accountIds)
            ORDER BY id
            FOR NO KEY UPDATE""", nativeQuery = true)
    List<Account> lockAccounts(UUID tenantId, Collection<UUID> accountIds);

}
