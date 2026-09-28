package com.example.ledgers.posting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PostingRepository extends JpaRepository<Posting, UUID> {

    /** Raw balance (signed minor units) of one account in one asset; 0 when it has no postings yet. */
    @Query("select coalesce(sum(p.amount), 0) from Posting p where p.accountId = :accountId and p.asset = :asset")
    long sumAmount(UUID accountId, String asset);
}
