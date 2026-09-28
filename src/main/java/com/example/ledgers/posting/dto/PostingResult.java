package com.example.ledgers.posting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PostingResult {
    private TransactionResponse transaction;
    private boolean replayed;                      // true when the Idempotency-Key was already used with this body
}
