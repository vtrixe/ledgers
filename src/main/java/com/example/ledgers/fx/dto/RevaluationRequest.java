package com.example.ledgers.fx.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
public class RevaluationRequest {

    private OffsetDateTime asOf;                   // usually the period end; closing rate = latest rate at or before it. Default: now
}
