package com.example.ledgers.fx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateFxRateRequest {

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like EUR")
    private String base;

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR")
    private String quote;

    @NotBlank
    @Pattern(regexp = "\\d{1,12}(\\.\\d{1,12})?", message = "must be a positive decimal string like \"83.4567\"")
    private String rate;

    @NotNull
    private OffsetDateTime asOf;

    private String source = "manual";
}
