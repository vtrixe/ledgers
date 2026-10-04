package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.posting.Asset;
import com.example.ledgers.posting.AssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;

/** Daily ECB reference rates (EUR base) from the free Frankfurter API. */
@Component
@Slf4j
@RequiredArgsConstructor
public class EcbRateFetcher {

    private static final ZoneId ECB_ZONE = ZoneId.of("Europe/Berlin");

    private final FxRateService fxRateService;
    private final AssetRepository assetRepository;
    private final RestClient.Builder restClientBuilder;

    @Value("${ledger.fx.ecb.enabled:false}")
    private boolean enabled;

    @Value("${ledger.fx.ecb.url:https://api.frankfurter.dev/v1/latest}")
    private String url;

    @Scheduled(cron = "${ledger.fx.ecb.cron:0 30 16 * * MON-FRI}", zone = "Europe/Berlin")
    public void scheduledFetch() {
        if (!enabled) {
            return;
        }
        try {
            fetch();
        } catch (RuntimeException e) {
            log.error("ECB rate fetch failed", e);
        }
    }

    public int fetch() {
        FrankfurterResponse response = restClientBuilder.build().get()
                .uri(url + "?base=EUR")
                .retrieve()
                .body(FrankfurterResponse.class);
        if (response == null || response.rates() == null) {
            throw new IllegalStateException("Empty response from " + url);
        }

        OffsetDateTime asOf = response.date().atTime(16, 0).atZone(ECB_ZONE).toInstant().atOffset(ZoneOffset.UTC);
        Set<String> knownAssets = new HashSet<>();
        for (Asset asset : assetRepository.findAll()) {
            knownAssets.add(asset.getCode());
        }

        List<CreateFxRateRequest> rates = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : response.rates().entrySet()) {
            if (knownAssets.contains(entry.getKey()) && !"EUR".equals(entry.getKey())) {
                rates.add(new CreateFxRateRequest("EUR", entry.getKey(), entry.getValue().toPlainString(), asOf, "ecb"));
            }
        }
        return fxRateService.addRates(rates).size();
    }

    record FrankfurterResponse(String base, LocalDate date, Map<String, BigDecimal> rates) {
    }
}
