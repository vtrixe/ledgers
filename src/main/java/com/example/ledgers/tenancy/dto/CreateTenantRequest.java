package com.example.ledgers.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateTenantRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR")
    private String functionalAsset;

    @NotBlank
    @Size(max = 64)
    private String reportingTimezone;              // IANA name, e.g. Asia/Kolkata
}
