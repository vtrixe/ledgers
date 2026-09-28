package com.example.ledgers.tenancy;

import java.util.List;
import java.util.UUID;

/** The authenticated caller of the tenant API, resolved from its API key. */
public record TenantPrincipal(UUID tenantId, UUID apiKeyId, List<String> scopes) {

    /** Recorded as created_by / changed_by by the database triggers. */
    public String actor() {
        return "api_key:" + apiKeyId;
    }

}
