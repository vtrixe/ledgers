package com.example.ledgers.tenancy;

import com.example.ledgers.tenancy.dto.CreateTenantRequest;
import com.example.ledgers.tenancy.dto.CreatedTenantResponse;
import com.example.ledgers.tenancy.dto.IssueKeyRequest;
import com.example.ledgers.tenancy.dto.IssuedKeyResponse;

import java.util.List;
import java.util.UUID;

public interface TenantAdminService {

    CreatedTenantResponse createTenant(CreateTenantRequest request);

    List<Tenant> listTenants();

    Tenant getTenant(UUID tenantId);

    Tenant deactivateTenant(UUID tenantId);

    IssuedKeyResponse rotateKey(UUID tenantId, IssueKeyRequest request);

    void revokeKey(UUID tenantId);
}
