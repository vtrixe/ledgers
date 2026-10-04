package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.CreateFxRateRequest;
import com.example.ledgers.schema.SchemaTestSupport;
import com.example.ledgers.shared.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** findRate on its own, one behaviour per test. Each test uses its own year so global rates never interfere. */
class FindRateTest extends SchemaTestSupport {

    @Autowired
    private FxRateService fxRateService;

    @Test
    void step1_usesTheLatestRateAtOrBeforeTheTime() {
        OffsetDateTime t = at(2010);
        rate("USD", "INR", "80.00", t.minusDays(2));
        rate("USD", "INR", "81.00", t.minusDays(1));
        rate("USD", "INR", "99.00", t.plusDays(1));

        FxQuote quote = fxRateService.findRate("USD", "INR", t.toInstant());

        assertThat(quote.rate()).isEqualByComparingTo("81.00");
        assertThat(quote.asOf()).isEqualTo(t.minusDays(1).toInstant());
    }

    @Test
    void step1_noRateIsAnApiError() {
        assertThatThrownBy(() -> fxRateService.findRate("GBP", "AED", at(2011).toInstant()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_NO_RATE");
    }

    @Test
    void step2_aRateOlderThanTheMaxAgeIsNotUsed() {
        OffsetDateTime t = at(2012);
        rate("GBP", "AED", "4.60", t.minusDays(8));

        assertThatThrownBy(() -> fxRateService.findRate("GBP", "AED", t.toInstant()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "ERR_NO_RATE");
    }

    @Test
    void step3_sameAssetIsRateOne() {
        assertThat(fxRateService.findRate("INR", "INR", at(2013).toInstant()).rate()).isEqualByComparingTo("1");
    }

    @Test
    void step4_crossRateThroughEur() {
        OffsetDateTime t = at(2014);
        rate("EUR", "SGD", "1.50", t.minusDays(2));
        rate("EUR", "JPY", "150", t.minusDays(1));

        FxQuote quote = fxRateService.findRate("SGD", "JPY", t.toInstant());

        assertThat(quote.rate()).isEqualByComparingTo("100");
        assertThat(quote.asOf()).isEqualTo(t.minusDays(2).toInstant());
    }

    private static OffsetDateTime at(int year) {
        return OffsetDateTime.of(year, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
    }

    private void rate(String base, String quote, String rate, OffsetDateTime asOf) {
        fxRateService.addRates(List.of(new CreateFxRateRequest(base, quote, rate, asOf, "test")));
    }
}
