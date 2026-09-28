package com.example.ledgers.tenancy;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class Tenant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "functional_asset", nullable = false)
    private String functionalAsset;                // frozen after the first transaction (DB trigger)

    @Column(name = "reporting_timezone", nullable = false)
    private String reportingTimezone;              // IANA name; frozen after the first period close

    @Column(name = "closed_through")
    private Instant closedThrough;                 // period lock: no postings effective at or before this

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deactivated_at")
    private Instant deactivatedAt;                 // "revoked" tenant; final once set

    public boolean isActive() {
        return deactivatedAt == null;
    }
}
