package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.PostTransactionRequest;
import com.example.ledgers.posting.dto.PostingResult;
import com.example.ledgers.posting.dto.TransactionResponse;
import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/transactions")
@RequiredArgsConstructor
@Tag(name = "Transactions", description = "Post and read double-entry transactions")
@SecurityRequirement(name = "ApiKey")
public class PostingController {

    private final PostingService postingService;

    @PostMapping
    @Operation(summary = "Post a transaction",
            description = "Postings must balance per asset. A retry with the same Idempotency-Key and body returns the "
                    + "original (200 + `Idempotent-Replayed: true`); the same key with a different body is rejected.")
    public ResponseEntity<CommonResponse<TransactionResponse>> postTransaction(
            @AuthenticationPrincipal TenantPrincipal principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request) {
        PostingResult result = postingService.postTransaction(principal, idempotencyKey, request);
        if (result.isReplayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(CommonResponse.ok("Already posted with this Idempotency-Key; returning the original",
                            result.getTransaction()));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.created("Transaction posted", result.getTransaction()));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a transaction with its postings")
    public CommonResponse<TransactionResponse> getTransaction(@AuthenticationPrincipal TenantPrincipal principal,
                                                              @PathVariable UUID transactionId) {
        return CommonResponse.ok("Transaction", postingService.getTransaction(principal, transactionId));
    }
}
