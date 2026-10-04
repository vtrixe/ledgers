package com.example.ledgers.fx.dto;

import com.example.ledgers.posting.dto.TransactionResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class ConversionResponse {
    private String fromAsset;
    private String fromAmount;
    private String toAsset;
    private String toAmount;
    private String rate;
    private String rateAsOf;
    private String rateSource;
    private boolean replayed;
    private TransactionResponse transaction;
}
