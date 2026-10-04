package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingResult;
import com.example.ledgers.posting.dto.TransactionResponse;
import com.example.ledgers.tenancy.TenantPrincipal;

import java.util.Optional;
import java.util.UUID;

public interface PostingService {

    PostingResult postTransaction(TenantPrincipal principal, String idempotencyKey, PostTransactionRequest request);

    TransactionResponse getTransaction(TenantPrincipal principal, UUID transactionId);

    Optional<PostingResult> findExisting(TenantPrincipal principal, String idempotencyKey);
}
