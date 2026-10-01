package com.example.ledgers.posting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BalanceResponse {
    private UUID accountId;                        // account balance only
    private String path;                           // account balance only
    private String prefix;                         // rollup only
    private String type;
    private Instant asOf;                          // postings effective at or before this
    private Instant knownAt;                       // postings recorded at or before this
    private List<AssetBalance> balances;
}
