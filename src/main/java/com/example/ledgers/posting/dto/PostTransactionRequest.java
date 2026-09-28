package com.example.ledgers.posting.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
public class PostTransactionRequest {

    private OffsetDateTime effectiveAt;

    private UUID reversesTransactionId;

    private Map<String, Object> metadata;

    @NotNull
    @Size(min = 2, max = 100)
    private List<@Valid PostingLineRequest> postings;
}
