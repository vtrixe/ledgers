package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.shared.ApiException;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class FxRateServiceImpl implements FxRateService {

    private static final String CROSS_CURRENCY = "EUR";
    private static final int RATE_SCALE = 12;

    private final FxRateRepository fxRateRepository;

    @Value("${ledger.fx.max-rate-age:P7D}")
    private Duration maxRateAge;

    @Override
    @Transactional(readOnly = true)
    public FxQuote findRate(String base, String quote, Instant at) {

        if(StringUtils.isBlank(base) || StringUtils.isBlank(quote)) {
            throw ApiException.unprocessable("ERR_MISSING_PARAM","Either base or quote is blank");
        }

        if(base.equalsIgnoreCase(quote)) {
            return new FxQuote(base,quote,BigDecimal.ONE,at,"identity");
        }

        Optional<FxRate> direct = findFreshRate(base, quote, at);
        if (direct.isPresent()) {
            FxRate fxRate = direct.get();
            return new FxQuote(base,quote,fxRate.getRate(),fxRate.getAsOf(),fxRate.getSource());
        }

        Optional<FxQuote> cross = findCrossRate(base, quote, at);
        if (cross.isPresent()) {
            return cross.get();
        }

        throw ApiException.unprocessable(
                "ERR_NO_RATE",
                "No rate for " + base + " -> " + quote + " at or before " + at
                        + " (direct or via " + CROSS_CURRENCY + ") within " + maxRateAge.toDays() + " days"
        );
    }

    private Optional<FxQuote> findCrossRate(String base, String quote, Instant at) {
        Optional<FxRate> crossToBase = findFreshRate(CROSS_CURRENCY, base, at);
        Optional<FxRate> crossToQuote = findFreshRate(CROSS_CURRENCY, quote, at);

        BigDecimal crossToBaseRate = base.equals(CROSS_CURRENCY) ? BigDecimal.ONE
                : crossToBase.map(FxRate::getRate).orElse(null);
        BigDecimal crossToQuoteRate = quote.equals(CROSS_CURRENCY) ? BigDecimal.ONE
                : crossToQuote.map(FxRate::getRate).orElse(null);

        if (Objects.isNull(crossToBaseRate) || Objects.isNull(crossToQuoteRate)) {
            return Optional.empty();
        }

        Instant crossToBaseAsOf = crossToBase.map(FxRate::getAsOf).orElse(at);
        Instant crossToQuoteAsOf = crossToQuote.map(FxRate::getAsOf).orElse(at);
        Instant asOf = crossToBaseAsOf.isBefore(crossToQuoteAsOf) ? crossToBaseAsOf : crossToQuoteAsOf;

        BigDecimal rate = crossToQuoteRate.divide(crossToBaseRate, RATE_SCALE, RoundingMode.HALF_EVEN);
        return Optional.of(new FxQuote(base, quote, rate, asOf, "cross:" + CROSS_CURRENCY));
    }

   private Optional<FxRate> findFreshRate(String base,String quote,Instant at) {
        Optional<FxRate> fxRate = fxRateRepository
                .findFirstByBaseAndQuoteAndAsOfLessThanEqualOrderByAsOfDesc(base, quote, at);

        if (fxRate.isEmpty()) {
            return Optional.empty();
        }

        if (Duration.between(fxRate.get().getAsOf(), at)
                .compareTo(maxRateAge) > 0) {
            return Optional.empty();
        }
        return fxRate;
    }

    @Override
    @Transactional
    public List<FxRate> addRates(List<CreateFxRateRequest> rates) {
        List<FxRate> saved = new ArrayList<>();
        for (CreateFxRateRequest request : rates) {
            Instant asOf = request.getAsOf().toInstant().truncatedTo(ChronoUnit.MICROS);
            String source = request.getSource() == null ? "manual" : request.getSource();
            if (fxRateRepository.existsByBaseAndQuoteAndAsOfAndSource(request.getBase(), request.getQuote(), asOf, source)) {
                continue;
            }
            saved.add(fxRateRepository.save(new FxRate()
                    .setBase(request.getBase())
                    .setQuote(request.getQuote())
                    .setRate(new BigDecimal(request.getRate()))
                    .setAsOf(asOf)
                    .setSource(source)));
        }
        log.info("Stored {} of {} fx rates", saved.size(), rates.size());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FxRate> listRates(String base, String quote) {
        return fxRateRepository.findTop100ByBaseAndQuoteOrderByAsOfDesc(base, quote);
    }
}
