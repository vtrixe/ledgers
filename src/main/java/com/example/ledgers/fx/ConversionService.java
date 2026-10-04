package com.example.ledgers.fx;

import com.example.ledgers.fx.dto.ConversionRequest;
import com.example.ledgers.fx.dto.ConversionResponse;
import com.example.ledgers.tenancy.TenantPrincipal;

public interface ConversionService {

    ConversionResponse convert(TenantPrincipal principal, String idempotencyKey, ConversionRequest request);
}
