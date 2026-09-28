package com.example.ledgers.tenancy;

import com.example.ledgers.shared.ApiException;
import com.example.ledgers.shared.LedgerSession;
import com.example.ledgers.tenancy.dto.CreateTenantRequest;
import com.example.ledgers.tenancy.dto.CreatedTenantResponse;
import com.example.ledgers.tenancy.dto.IssueKeyRequest;
import com.example.ledgers.tenancy.dto.IssuedKeyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class TenantAdminServiceImpl implements TenantAdminService {

    private static final String ACTOR = "root";

    private final TenantRepository tenantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final LedgerSession ledgerSession;
    private final Clock clock;

    @Override
    @Transactional
    public CreatedTenantResponse createTenant(CreateTenantRequest request) {
        ledgerSession.bindActor(ACTOR);
        Tenant tenant = tenantRepository.saveAndFlush(new Tenant()
                .setName(request.getName())
                .setFunctionalAsset(request.getFunctionalAsset())
                .setReportingTimezone(request.getReportingTimezone()));
        IssuedKeyResponse key = issueKey(tenant.getId(), "default", Scopes.ALL, null);
        log.info("Created tenant={} name={}", tenant.getId(), tenant.getName());
        return new CreatedTenantResponse(tenant, key);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tenant> listTenants() {
        return tenantRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Tenant getTenant(UUID tenantId) {
        return findTenant(tenantId);
    }

    @Override
    @Transactional
    public Tenant deactivateTenant(UUID tenantId) {
        ledgerSession.bindActor(ACTOR);
        Tenant tenant = findActiveTenant(tenantId);
        Instant now = clock.instant();
        apiKeyRepository.findByTenantIdAndRevokedAtIsNull(tenantId).ifPresent(key -> key.setRevokedAt(now));
        tenant.setDeactivatedAt(now);
        log.info("Deactivated tenant={}", tenantId);
        return tenant;
    }

    /** Revokes the current key and issues a new one in the same transaction (one active key per tenant). */
    @Override
    @Transactional
    public IssuedKeyResponse rotateKey(UUID tenantId, IssueKeyRequest request) {
        ledgerSession.bindActor(ACTOR);
        findActiveTenant(tenantId);
        List<String> scopes = request.getScopes() == null || request.getScopes().isEmpty() ? Scopes.ALL : request.getScopes();
        if (!Scopes.ALL.containsAll(scopes)) {
            throw ApiException.badRequest("ERR_INVALID_SCOPE", "Scopes must be among " + Scopes.ALL);
        }

        apiKeyRepository.findByTenantIdAndRevokedAtIsNull(tenantId).ifPresent(current -> {
            current.setRevokedAt(clock.instant());
            apiKeyRepository.saveAndFlush(current);
        });
        return issueKey(tenantId, request.getName(), scopes, request.getExpiresAt());
    }

    @Override
    @Transactional
    public void revokeKey(UUID tenantId) {
        ledgerSession.bindActor(ACTOR);
        ApiKey key = apiKeyRepository.findByTenantIdAndRevokedAtIsNull(tenantId)
                .orElseThrow(() -> ApiException.notFound("ERR_API_KEY_NOT_FOUND", "Tenant " + tenantId + " has no active key"));
        key.setRevokedAt(clock.instant());
    }

    private IssuedKeyResponse issueKey(UUID tenantId, String name, List<String> scopes, Instant expiresAt) {
        String plain = ApiKeyCodec.generate();
        ApiKey key = apiKeyRepository.saveAndFlush(new ApiKey()
                .setTenantId(tenantId)
                .setKeyHash(ApiKeyCodec.hash(plain))
                .setHint(ApiKeyCodec.hint(plain))
                .setName(name)
                .setScopes(scopes)
                .setExpiresAt(expiresAt));
        return new IssuedKeyResponse(key.getId(), plain, key.getHint(), key.getName(), key.getScopes(), key.getExpiresAt());
    }

    private Tenant findTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> ApiException.notFound("ERR_TENANT_NOT_FOUND", "No tenant " + tenantId));
    }

    private Tenant findActiveTenant(UUID tenantId) {
        Tenant tenant = findTenant(tenantId);
        if (!tenant.isActive()) {
            throw ApiException.conflict("ERR_TENANT_DEACTIVATED", "Tenant " + tenantId + " is deactivated");
        }
        return tenant;
    }
}
