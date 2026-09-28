package com.example.ledgers.tenancy.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code key} is the only time the plain key is ever shown. */
@Data
@AllArgsConstructor
public class IssuedKeyResponse {
    private UUID id;
    private String key;
    private String hint;
    private String name;
    private List<String> scopes;
    private Instant expiresAt;
}
