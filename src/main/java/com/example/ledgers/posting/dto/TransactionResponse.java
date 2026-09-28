package com.example.ledgers.posting.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
public class TransactionResponse {
    private UUID id;
    private long seq;
    private String idempotencyKey;
    private Instant effectiveAt;
    private Instant recordedAt;
    private UUID reversesTransactionId;
    private String createdBy;
    private Map<String, Object> metadata;
    private List<PostingResponse> postings;

    @Data
    @AllArgsConstructor
    public static class PostingResponse {
        private UUID id;
        private UUID accountId;
        private String account;
        private String asset;
        private String amount;                     // decimal string in the asset's scale, e.g. "-450.00"
    }
}
