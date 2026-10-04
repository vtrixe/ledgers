package com.example.ledgers;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails on module cycles or on a module using another module's internal (non-exposed) types. No database needed. */
class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(LedgersApplication.class).verify();
    }
}
