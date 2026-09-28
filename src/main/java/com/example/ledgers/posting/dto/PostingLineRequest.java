package com.example.ledgers.posting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostingLineRequest {

    @NotBlank
    @Size(max = 255)
    private String account;                        // account path, e.g. assets:psp:razorpay:clearing

    @NotBlank
    @Pattern(regexp = "[A-Z]{3}", message = "must be an ISO 4217 code like INR")
    private String asset;

    // Decimal string, never a JSON number (no binary floating point). Debit positive, credit negative.
    @NotBlank
    @Pattern(regexp = "-?\\d{1,19}(\\.\\d{1,18})?", message = "must be a decimal string like \"-450.00\"")
    private String amount;
}
