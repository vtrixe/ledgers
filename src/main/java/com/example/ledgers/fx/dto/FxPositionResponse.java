package com.example.ledgers.fx.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
public class FxPositionResponse {
    private Instant asOf;
    private String functionalAsset;
    private List<Position> positions;              // one per trading account (equity:fx:trading:<asset>)
    private long netValueMinorUnits;               // sum of position values, in the functional asset, signed as stored
    private String netValue;

    @Data
    @AllArgsConstructor
    public static class Position {
        private String account;
        private String asset;
        private long rawMinorUnits;                // trading account balance in its own asset, signed as stored
        private String rate;                       // 1 asset = rate functional asset
        private String rateAsOf;
        private long valueMinorUnits;              // in the functional asset, signed as stored
        private String value;
    }
}
