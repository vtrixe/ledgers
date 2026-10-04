package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.ConversionRequest;
import com.example.ledgers.fx.dto.ConversionResponse;
import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/conversions")
@RequiredArgsConstructor
@Tag(name = "FX", description = "Currency conversions, FX position and revaluation")
@SecurityRequirement(name = "ApiKey")
public class ConversionController {

    private final ConversionService conversionService;

    @PostMapping
    @Operation(summary = "Convert between currencies",
            description = "Books four postings through the per-asset trading accounts at the latest rate at or before "
                    + "effectiveAt. A retry with the same Idempotency-Key replays the original, even if a newer rate exists.")
    public ResponseEntity<CommonResponse<ConversionResponse>> convert(@AuthenticationPrincipal TenantPrincipal principal,
                                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                      @Valid @RequestBody ConversionRequest request) {
        ConversionResponse response = conversionService.convert(principal, idempotencyKey, request);
        if (response.isReplayed()) {
            return ResponseEntity.ok()
                    .header("Idempotent-Replayed", "true")
                    .body(CommonResponse.ok("Already converted with this Idempotency-Key; returning the original", response));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.created("Converted", response));
    }
}
