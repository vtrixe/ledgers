package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.shared.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/v1/fx-rates")
@RequiredArgsConstructor
@Tag(name = "Admin — FX rates", description = "Reference exchange rates (append-only) — requires the root key")
@SecurityRequirement(name = "RootKey")
public class FxRateAdminController {

    private final FxRateService fxRateService;
    private final EcbRateFetcher ecbRateFetcher;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add rates", description = "Rates already stored for the same (base, quote, asOf, source) are skipped.")
    public CommonResponse<List<FxRate>> addRates(@RequestBody List<@Valid CreateFxRateRequest> rates) {
        return CommonResponse.created("Rates stored", fxRateService.addRates(rates));
    }

    @GetMapping
    @Operation(summary = "Latest 100 rates for a pair")
    public CommonResponse<List<FxRate>> listRates(@RequestParam String base, @RequestParam String quote) {
        return CommonResponse.ok("Rates", fxRateService.listRates(base, quote));
    }

    @PostMapping("/fetch-ecb")
    @Operation(summary = "Fetch today's ECB rates now", description = "Same as the daily scheduled job.")
    public CommonResponse<Integer> fetchEcb() {
        return CommonResponse.ok("ECB rates fetched", ecbRateFetcher.fetch());
    }
}
