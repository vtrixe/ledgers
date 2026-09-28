package com.example.ledgers.posting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/** One line of a transaction. Signed minor units: debit positive, credit negative. */
@Entity
@Table(name = "postings")
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Posting {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "asset", nullable = false)
    private String asset;

    @Column(name = "amount", nullable = false)
    private long amount;                           // minor units, e.g. 50000 = ₹500.00
}
