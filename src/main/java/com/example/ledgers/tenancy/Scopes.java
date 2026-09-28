package com.example.ledgers.tenancy;

import lombok.experimental.UtilityClass;

import java.util.List;

/** API-key scopes; must match the CHECK constraint on api_keys.scopes. */
@UtilityClass
public class Scopes {

    public final String LEDGER_READ = "ledger:read";
    public final String LEDGER_WRITE = "ledger:write";
    public final String ACCOUNTS_MANAGE = "accounts:manage";

    public final List<String> ALL = List.of(LEDGER_READ, LEDGER_WRITE, ACCOUNTS_MANAGE);
}
