package com.example.ledgers.shared;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Per-transaction database settings read by the triggers. */
@Component
@RequiredArgsConstructor
public class LedgerSession {

    private final EntityManager entityManager;

    /**
     * Records who is making changes in this transaction (created_by, history changed_by).
     * Transaction-local, so it can never leak to the next request on a pooled connection.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bindActor(String actor) {
        entityManager.createNativeQuery("SELECT set_config('ledger.actor', :actor, true)")
                .setParameter("actor", actor)
                .getSingleResult();
    }
}
