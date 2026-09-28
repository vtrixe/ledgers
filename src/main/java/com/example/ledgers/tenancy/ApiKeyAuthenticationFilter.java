package com.example.ledgers.tenancy;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates {@code Authorization: Bearer <key>}: the root key (from the environment) on /admin, a tenant API key
 * everywhere else. An unknown key leaves the request unauthenticated, so Spring Security answers 401.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ROLE_ROOT = "ROLE_ROOT";
    private static final int MIN_ROOT_KEY_LENGTH = 32;

    private final ApiKeyRepository apiKeyRepository;

    @Value("${ledger.root-key:}")
    private String rootKey;

    private byte[] rootKeyHash;

    @PostConstruct
    void init() {
        if (rootKey == null || rootKey.length() < MIN_ROOT_KEY_LENGTH) {
            log.warn("LEDGER_ROOT_KEY is not set (or shorter than {} chars): the admin API is disabled", MIN_ROOT_KEY_LENGTH);
        } else {
            rootKeyHash = ApiKeyCodec.hash(rootKey);
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length()).trim();
            Optional<Authentication> authentication = request.getRequestURI().startsWith("/admin/")
                    ? authenticateRoot(token)
                    : authenticateTenant(token);
            authentication.ifPresentOrElse(auth -> {
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);
            }, () -> log.warn("Rejected bearer key on {}", request.getRequestURI()));
        }
        chain.doFilter(request, response);
    }

    private Optional<Authentication> authenticateRoot(String token) {
        // Fixed-length digests compared in constant time: response timing reveals nothing about the key.
        if (rootKeyHash == null || !MessageDigest.isEqual(rootKeyHash, ApiKeyCodec.hash(token))) {
            return Optional.empty();
        }
        return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                "root", null, List.of(new SimpleGrantedAuthority(ROLE_ROOT))));
    }

    private Optional<Authentication> authenticateTenant(String token) {
        return apiKeyRepository.findUsableByKeyHash(ApiKeyCodec.hash(token))
                .map(key -> {
                    TenantPrincipal principal = new TenantPrincipal(key.getTenantId(), key.getId(), key.getScopes());
                    List<SimpleGrantedAuthority> authorities = key.getScopes().stream()
                            .map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                            .toList();
                    return UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
                });
    }
}
