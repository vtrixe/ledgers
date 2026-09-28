package com.example.ledgers.posting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.experimental.UtilityClass;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SHA-256 of a canonical form of the request, stored as transactions.request_hash to detect an idempotency key
 * reused with a different body. A genuine retry may be re-encoded differently (key order, "500.0" vs "500.00",
 * +05:30 vs Z), so hash a canonical form, not the raw bytes.
 */
@UtilityClass
class RequestHasher {

    private final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    record CanonicalLeg(String account, String asset, long amount) {
    }
    private record CanonicalRequest(String effectiveAt, UUID reversesTransactionId,
                                    Map<String, Object> metadata, List<CanonicalLeg> postings) {
    }

    byte[] hash(Instant effectiveAt, UUID reversesTransactionId, Map<String, Object> metadata, List<CanonicalLeg> postings) {
        CanonicalRequest canonical = new CanonicalRequest(
                effectiveAt == null ? null : effectiveAt.toString(),
                reversesTransactionId,
                metadata == null ? Map.of() : metadata,
                postings);
        try {
            return MessageDigest.getInstance("SHA-256").digest(CANONICAL.writeValueAsBytes(canonical));
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

}
