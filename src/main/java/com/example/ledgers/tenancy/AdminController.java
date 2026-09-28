package com.example.ledgers.tenancy;

import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.dto.CreateTenantRequest;
import com.example.ledgers.tenancy.dto.CreatedTenantResponse;
import com.example.ledgers.tenancy.dto.IssueKeyRequest;
import com.example.ledgers.tenancy.dto.IssuedKeyResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/v1/tenants")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Tenant and API-key management — requires the root key")
@SecurityRequirement(name = "RootKey")
public class AdminController {

    private final TenantAdminService tenantAdminService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a tenant", description = "Creates the tenant and its first API key (all scopes). The key is returned once and never again.")
    public CommonResponse<CreatedTenantResponse> createTenant(@Valid @RequestBody CreateTenantRequest request) {
        return CommonResponse.created("Tenant created. Store the API key now: it is never shown again.",
                tenantAdminService.createTenant(request));
    }

    @GetMapping
    @Operation(summary = "List tenants")
    public CommonResponse<List<Tenant>> listTenants() {
        return CommonResponse.ok("Tenants", tenantAdminService.listTenants());
    }

    @GetMapping("/{tenantId}")
    @Operation(summary = "Get a tenant")
    public CommonResponse<Tenant> getTenant(@PathVariable UUID tenantId) {
        return CommonResponse.ok("Tenant", tenantAdminService.getTenant(tenantId));
    }

    @PostMapping("/{tenantId}/deactivate")
    @Operation(summary = "Deactivate (revoke) a tenant", description = "Final. Revokes its API key; the database rejects any further postings.")
    public CommonResponse<Tenant> deactivateTenant(@PathVariable UUID tenantId) {
        return CommonResponse.ok("Tenant deactivated", tenantAdminService.deactivateTenant(tenantId));
    }

    @PostMapping("/{tenantId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Rotate the API key", description = "Revokes the current key and issues a new one. The new key is returned once.")
    public CommonResponse<IssuedKeyResponse> rotateKey(@PathVariable UUID tenantId, @Valid @RequestBody IssueKeyRequest request) {
        return CommonResponse.created("New API key issued; the previous one is revoked. Store it now: it is never shown again.",
                tenantAdminService.rotateKey(tenantId, request));
    }

    @PostMapping("/{tenantId}/api-keys/revoke")
    @Operation(summary = "Revoke the API key", description = "The tenant has no working key until a new one is issued.")
    public CommonResponse<Void> revokeKey(@PathVariable UUID tenantId) {
        tenantAdminService.revokeKey(tenantId);
        return CommonResponse.ok("API key revoked", null);
    }
}
