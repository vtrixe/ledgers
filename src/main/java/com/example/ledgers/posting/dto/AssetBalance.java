package com.example.ledgers.posting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AssetBalance {
    private String asset;
    private String balance;                        // display balance in the asset's scale, e.g. "20.00"
    private long rawMinorUnits;                    // SUM(amount) as stored: debit positive, credit negative
}
