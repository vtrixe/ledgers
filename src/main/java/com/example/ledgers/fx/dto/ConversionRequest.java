package com.example.ledgers.fx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
public class ConversionRequest {

    @NotBlank
    @Size(max = 255)
    private String fromAccount;                    // account path money leaves, e.g. liabilities:wallets:user_7

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like USD")
    private String fromAsset;

    @NotBlank
    @Size(max = 255)
    private String toAccount;                      // account path money arrives in (may be the same account)

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR")
    private String toAsset;

    @NotBlank
    @Pattern(regexp = "\\d{1,19}(\\.\\d{1,18})?", message = "must be a positive decimal string like \"10.00\"")
    private String amount;                         // in fromAsset

    private OffsetDateTime effectiveAt;            // also picks the rate: latest rate at or before it. Default: now

    private Map<String, Object> metadata;          // "fx" is reserved
}
