package com.example.ledgers.posting;

import com.example.ledgers.posting.dto.BalanceResponse;
import com.example.ledgers.posting.dto.StatementResponse;
import com.example.ledgers.tenancy.TenantPrincipal;

import java.time.OffsetDateTime;

/** {@code account} is either the account id or its current path. */
public interface BalanceService {

    BalanceResponse getAccountBalance(TenantPrincipal principal, String account, OffsetDateTime asOf, OffsetDateTime knownAt);

    BalanceResponse getRollupBalance(TenantPrincipal principal, String prefix, OffsetDateTime asOf, OffsetDateTime knownAt);

    StatementResponse getStatement(TenantPrincipal principal, String account, String cursor, int limit);
}
