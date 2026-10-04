package com.example.ledgers.fx;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fx_rates")
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class FxRate {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "base", nullable = false)
    private String base;

    @Column(name = "quote", nullable = false)
    private String quote;

    @Column(name = "rate", nullable = false, precision = 24, scale = 12)
    private BigDecimal rate;

    @Column(name = "as_of", nullable = false)
    private Instant asOf;

    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "recorded_at", insertable = false, updatable = false)
    private Instant recordedAt;
}
