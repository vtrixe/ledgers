package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;

import java.time.Instant;
import java.util.List;

public interface FxRateService {

    FxQuote findRate(String base, String quote, Instant at);

    List<FxRate> addRates(List<CreateFxRateRequest> rates);

    List<FxRate> listRates(String base, String quote);
}
