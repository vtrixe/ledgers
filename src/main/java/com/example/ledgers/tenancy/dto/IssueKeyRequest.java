package com.example.ledgers.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
public class IssueKeyRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    private List<String> scopes;                   // defaults to all scopes

    private Instant expiresAt;
}
