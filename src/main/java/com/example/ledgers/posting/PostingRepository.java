package com.example.ledgers.posting;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {

    /** Raw balance (signed minor units) of one account in one asset; 0 when it has no postings yet. */
    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.accountId = :accountId and p.asset = :asset")
    long sumAmount(UUID accountId, String asset);

    @Query("""
            select p.asset as asset, sum(p.amount) as total
            from Posting p join p.transaction t
            where p.tenantId = :tenantId and p.accountId = :accountId
              and t.effectiveAt <= :asOf and t.recordedAt <= :knownAt
            group by p.asset""")
    List<AssetTotal> sumByAsset(UUID tenantId, UUID accountId, Instant asOf, Instant knownAt);

    @Query("""
            select p.asset as asset, sum(p.amount) as total
            from Posting p join p.transaction t
            where p.tenantId = :tenantId and p.accountId in :accountIds
              and t.effectiveAt <= :asOf and t.recordedAt <= :knownAt
            group by p.asset""")
    List<AssetTotal> sumByAssetForAccounts(UUID tenantId, Collection<UUID> accountIds, Instant asOf, Instant knownAt);

    @Query("""
        select p from Posting p join fetch p.transaction t
        where p.tenantId = :tenantId and p.accountId = :accountId
        order by t.seq desc, p.id desc""")
    List<Posting> findStatementFirstPage(UUID tenantId, UUID accountId, Limit limit);

    @Query("""
        select p from Posting p join fetch p.transaction t
        where p.tenantId = :tenantId and p.accountId = :accountId
          and (t.seq < :seq or (t.seq = :seq and p.id < :postingId))
        order by t.seq desc, p.id desc""")
    List<Posting> findStatementPageBefore(UUID tenantId, UUID accountId, long seq, UUID postingId, Limit limit);
}
