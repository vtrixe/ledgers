package com.example.ledgers.posting;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** Global ISO 4217 reference data. Scale (decimal places) can never change. */
@Entity
@Table(name = "assets")
@Immutable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Asset {

    @Id
    @Column(name = "code")
    private String code;                           // e.g. INR

    @Column(name = "scale", nullable = false)
    private short scale;                           // INR 2, JPY 0, KWD 3

    @Column(name = "name", nullable = false)
    private String name;
}
