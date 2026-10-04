package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.FxPositionResponse;
import com.example.ledgers.fx.dto.RevaluationRequest;
import com.example.ledgers.fx.dto.RevaluationResponse;
import com.example.ledgers.tenancy.TenantPrincipal;

import java.time.OffsetDateTime;

public interface FxPositionService {

    FxPositionResponse getPosition(TenantPrincipal principal, OffsetDateTime asOf);

    RevaluationResponse revalue(TenantPrincipal principal, String idempotencyKey, RevaluationRequest request);
}
