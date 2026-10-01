package com.example.ledgers.posting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class StatementResponse {
    private UUID accountId;
    private String path;
    private List<Entry> entries;                   // newest first (by ledger seq)
    private String nextCursor;                     // pass as ?cursor= for the next page; null on the last page

    @Data
    @AllArgsConstructor
    public static class Entry {
        private UUID postingId;
        private UUID transactionId;
        private long seq;
        private Instant effectiveAt;
        private Instant recordedAt;
        private String asset;
        private String amount;                     // display sign, like the balance: lines add up to it
    }
}
