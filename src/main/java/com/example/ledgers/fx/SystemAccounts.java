package com.example.ledgers.fx;

import com.example.ledgers.accounts.AccountService;
import com.example.ledgers.shared.ApiException;
import com.example.ledgers.tenancy.TenantPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** FX system accounts, created the first time a tenant needs them (allow_negative = true). */
@Component
@RequiredArgsConstructor
public class SystemAccounts {

    public static final String TRADING_PREFIX = "equity:fx:trading:";
    public static final String UNREALIZED_GAIN = "revenue:fx:unrealized_gain";
    public static final String UNREALIZED_LOSS = "expenses:fx:unrealized_loss";

    private final AccountService accountService;

    public static String tradingAccount(String asset) {
        return TRADING_PREFIX + asset.toLowerCase(Locale.ROOT);
    }

    public String ensure(TenantPrincipal principal, String path) {
        try {
            accountService.resolvePaths(principal.tenantId(), Set.of(path));
            return path;
        } catch (ApiException e) {
            if (!"ERR_ACCOUNT_NOT_FOUND".equals(e.getCode())) {
                throw e;
            }
        }
        try {
            accountService.createSystemAccount(principal, path);
        } catch (DataIntegrityViolationException e) {
            accountService.resolvePaths(principal.tenantId(), Set.of(path));
        }
        return path;
    }
}
