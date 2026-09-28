package com.example.ledgers.tenancy;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByTenantIdAndRevokedAtIsNull(UUID tenantId);

    /** The key authenticates only if it is active, unexpired, and its tenant is not deactivated. */
    @Query("""
            select k from ApiKey k join Tenant t on t.id = k.tenantId
            where k.keyHash = :keyHash
              and k.revokedAt is null
              and (k.expiresAt is null or k.expiresAt > current_timestamp)
              and t.deactivatedAt is null""")
    Optional<ApiKey> findUsableByKeyHash(byte[] keyHash);
}
