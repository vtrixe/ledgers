package com.example.ledgers.fx;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    boolean existsByBaseAndQuoteAndAsOfAndSource(String base, String quote, Instant asOf, String source);

    List<FxRate> findTop100ByBaseAndQuoteOrderByAsOfDesc(String base, String quote);

    Optional<FxRate> findFirstByBaseAndQuoteAndAsOfLessThanEqualOrderByAsOfDesc(
            String base,
            String quote,
            Instant asOf
    );
}
