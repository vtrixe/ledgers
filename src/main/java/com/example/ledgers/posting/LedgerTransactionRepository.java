package com.example.ledgers.posting;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    @EntityGraph(attributePaths = "postings")
    Optional<LedgerTransaction> findByTenantIdAndIdempotencyKey(UUID tenantId, String idempotencyKey);

    @EntityGraph(attributePaths = "postings")
    Optional<LedgerTransaction> findByIdAndTenantId(UUID id, UUID tenantId);



}
