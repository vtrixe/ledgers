package com.example.ledgers.fx;

import java.math.BigDecimal;
import java.time.Instant;

/** 1 {@code base} = {@code rate} {@code quote}, as of {@code asOf}, from {@code source}. */
public record FxQuote(String base, String quote, BigDecimal rate, Instant asOf, String source) {
}
