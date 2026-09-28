package com.example.ledgers.accounts;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Balances are never stored here: they are always derived from postings. */
@Entity
@Table(name = "accounts")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Account {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "path", nullable = false)
    private String path;                           // e.g. assets:psp:razorpay:clearing; renamable, type segment is not

    @Generated
    @Column(name = "type", insertable = false, updatable = false)
    private String type;                           // derived by Postgres from the first path segment

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false)
    private Map<String, Object> metadata = Map.of();

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    /** +1 for assets/expenses (debits increase them), -1 otherwise: display balance = SUM(amount) x normalSign. */
    public int normalSign() {
        return "assets".equals(type) || "expenses".equals(type) ? 1 : -1;
    }
}
