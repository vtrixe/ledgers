package com.example.ledgers.tenancy.dto;

import com.example.ledgers.tenancy.Tenant;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreatedTenantResponse {
    private Tenant tenant;
    private IssuedKeyResponse apiKey;
}
