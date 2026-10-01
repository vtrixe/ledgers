package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.BalanceResponse;
import com.example.ledgers.posting.dto.StatementResponse;
import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequiredArgsConstructor
@Tag(name = "Balances", description = "Balances derived from postings, at any point in time, and account statements")
@SecurityRequirement(name = "ApiKey")
public class BalanceController {

    private static final String ACCOUNT = "Account id, or its current path (e.g. assets:psp:razorpay:clearing)";
    private static final String AS_OF = "Postings effective at or before this time (offset required; encode + as %2B). Default: now";
    private static final String KNOWN_AT = "Only postings recorded at or before this time: reproduces what was reported then. Default: now";

    private final BalanceService balanceService;

    @GetMapping("/v1/accounts/{account}/balances")
    @Operation(summary = "Account balance per asset", description = "Display balance uses the account type's normal sign.")
    public CommonResponse<BalanceResponse> getAccountBalance(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Parameter(description = ACCOUNT) @PathVariable String account,
            @Parameter(description = AS_OF) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf,
            @Parameter(description = KNOWN_AT) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime knownAt) {
        return CommonResponse.ok("Account balance", balanceService.getAccountBalance(principal, account, asOf, knownAt));
    }

    @GetMapping("/v1/balances")
    @Operation(summary = "Rollup balance per asset", description = "Total across all accounts whose path starts with the prefix, e.g. `revenue:fees:`.")
    public CommonResponse<BalanceResponse> getRollupBalance(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Parameter(description = "Path prefix starting with the account type") @RequestParam String prefix,
            @Parameter(description = AS_OF) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf,
            @Parameter(description = KNOWN_AT) @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime knownAt) {
        return CommonResponse.ok("Rollup balance", balanceService.getRollupBalance(principal, prefix, asOf, knownAt));
    }

    @GetMapping("/v1/accounts/{account}/postings")
    @Operation(summary = "Account statement", description = "Postings newest first. Pass nextCursor back as cursor for the next page.")
    public CommonResponse<StatementResponse> getStatement(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Parameter(description = ACCOUNT) @PathVariable String account,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return CommonResponse.ok("Account statement", balanceService.getStatement(principal, account, cursor, limit));
    }
}
