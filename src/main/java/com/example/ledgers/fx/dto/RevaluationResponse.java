package com.example.ledgers.fx.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.example.ledgers.posting.dto.TransactionResponse;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RevaluationResponse {
    private boolean booked;                        // false when there was nothing to book at this rate
    private FxPositionResponse position;           // the position that was revalued (before booking)
    private TransactionResponse transaction;       // the revaluation posting, when booked
}
