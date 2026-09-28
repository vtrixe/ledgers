package com.example.ledgers.posting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The header of a double-entry transaction. Append-only: every column is updatable = false, so Hibernate never issues
 * an UPDATE (and the database rejects one anyway). seq, recorded_at and created_by are set by the insert trigger.
 * Not @Immutable: Hibernate 7 cannot re-read generated columns of an @Immutable entity.
 */
@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class LedgerTransaction {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Generated
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;                              // gap-free per tenant

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @JsonIgnore
    @ToString.Exclude
    @Column(name = "request_hash", nullable = false, updatable = false)
    private byte[] requestHash;

    @Generated(writable = true)
    @Column(name = "effective_at", updatable = false)
    private Instant effectiveAt;                   // when it happened; defaults to recorded_at

    @Generated
    @Column(name = "recorded_at", insertable = false, updatable = false)
    private Instant recordedAt;                    // when the ledger learned about it (database clock)

    @Column(name = "posting_count", nullable = false, updatable = false)
    private int postingCount;

    @Column(name = "reverses_transaction_id", updatable = false)
    private UUID reversesTransactionId;

    @Generated
    @Column(name = "created_by", insertable = false, updatable = false)
    private String createdBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, updatable = false)
    private Map<String, Object> metadata = Map.of();

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private List<Posting> postings = new ArrayList<>();

    public LedgerTransaction addPosting(Posting posting) {
        posting.setTransaction(this);
        postings.add(posting);
        return this;
    }
}
