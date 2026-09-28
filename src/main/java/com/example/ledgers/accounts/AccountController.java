package com.example.ledgers.accounts;

import com.example.ledgers.accounts.dto.CreateAccountRequest;
import com.example.ledgers.accounts.dto.UpdateAccountRequest;
import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Chart of accounts: hierarchical paths whose first segment is the account type")
@SecurityRequirement(name = "ApiKey")
public class AccountController {

    private final AccountService accountService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an account", description = "Path like `assets:psp:razorpay:clearing`; a retired path can never be reused.")
    public CommonResponse<Account> createAccount(@AuthenticationPrincipal TenantPrincipal principal,
                                                 @Valid @RequestBody CreateAccountRequest request) {
        return CommonResponse.created("Account created", accountService.createAccount(principal, request));
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get an account")
    public CommonResponse<Account> getAccount(@AuthenticationPrincipal TenantPrincipal principal,
                                              @PathVariable UUID accountId) {
        return CommonResponse.ok("Account", accountService.getAccount(principal, accountId));
    }

    @GetMapping
    @Operation(summary = "List accounts", description = "Optionally by path prefix, e.g. `revenue:fees:`.")
    public CommonResponse<List<Account>> listAccounts(@AuthenticationPrincipal TenantPrincipal principal,
                                                      @Parameter(description = "Path prefix") @RequestParam(required = false) String prefix,
                                                      @RequestParam(defaultValue = "100") int limit) {
        return CommonResponse.ok("Accounts", accountService.listAccounts(principal, prefix, limit));
    }

    @PatchMapping("/{accountId}")
    @Operation(summary = "Update an account", description = "Rename, toggle allowNegative or replace metadata. Every change is recorded in history.")
    public CommonResponse<Account> updateAccount(@AuthenticationPrincipal TenantPrincipal principal,
                                                 @PathVariable UUID accountId,
                                                 @Valid @RequestBody UpdateAccountRequest request) {
        return CommonResponse.ok("Account updated", accountService.updateAccount(principal, accountId, request));
    }
}
