package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.FxPositionResponse;
import com.example.ledgers.fx.dto.RevaluationRequest;
import com.example.ledgers.fx.dto.RevaluationResponse;
import com.example.ledgers.shared.CommonResponse;
import com.example.ledgers.tenancy.TenantPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/v1/fx")
@RequiredArgsConstructor
@Tag(name = "FX", description = "Currency conversions, FX position and revaluation")
@SecurityRequirement(name = "ApiKey")
public class FxController {

    private final FxPositionService fxPositionService;

    @GetMapping("/position")
    @Operation(summary = "FX position", description = "Trading account balances valued in the functional currency at the closing rate.")
    public CommonResponse<FxPositionResponse> getPosition(
            @AuthenticationPrincipal TenantPrincipal principal,
            @Parameter(description = "Valuation time (offset required; encode + as %2B). Default: now")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime asOf) {
        return CommonResponse.ok("FX position", fxPositionService.getPosition(principal, asOf));
    }

    @PostMapping("/revaluations")
    @Operation(summary = "Book FX revaluation", description = "Books the unrealized FX result at the closing rate (IAS 21). "
            + "Nothing is booked when the position is already valued at that rate.")
    public CommonResponse<RevaluationResponse> revalue(@AuthenticationPrincipal TenantPrincipal principal,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @RequestBody RevaluationRequest request) {
        return CommonResponse.ok("FX revaluation", fxPositionService.revalue(principal, idempotencyKey, request));
    }
}
