-- Tenants cannot be deleted (audit trail), so "revoking" a tenant deactivates it. Deactivation is final.

ALTER TABLE tenants ADD COLUMN deactivated_at timestamptz;
ALTER TABLE tenants_history ADD COLUMN deactivated_at timestamptz;

CREATE OR REPLACE FUNCTION tenants_before_write() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = NEW.reporting_timezone) THEN
        RAISE EXCEPTION 'LEDGER_INVALID: unknown time zone %', NEW.reporting_timezone
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.closed_through > now() THEN
        RAISE EXCEPTION 'LEDGER_INVALID: closed_through % is in the future', NEW.closed_through
            USING ERRCODE = 'check_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.deactivated_at IS NOT NULL THEN
            RAISE EXCEPTION 'LEDGER_INVALID: a tenant cannot be created deactivated'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF NEW.id <> OLD.id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: tenant id and created_at can never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.deactivated_at IS NOT NULL AND NEW.deactivated_at IS DISTINCT FROM OLD.deactivated_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: tenant % is deactivated and stays deactivated', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF NEW.functional_asset <> OLD.functional_asset
       AND EXISTS (SELECT 1 FROM transactions WHERE tenant_id = OLD.id) THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: functional_asset of tenant % cannot change after its first transaction', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Closed periods would silently cover different hours.
    IF NEW.reporting_timezone <> OLD.reporting_timezone AND OLD.closed_through IS NOT NULL THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: reporting_timezone of tenant % cannot change after a period has been closed', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    -- Moving the lock backwards reopens a closed period: allowed only as a deliberate admin action.
    IF OLD.closed_through IS NOT NULL
       AND (NEW.closed_through IS NULL OR NEW.closed_through < OLD.closed_through)
       AND coalesce(current_setting('ledger.allow_reopen', true), '') <> 'on' THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_REOPEN: moving closed_through back reopens a closed period; run SET LOCAL ledger.allow_reopen = ''on'' to do it deliberately'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION tenants_record_history() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD IS NOT DISTINCT FROM NEW THEN
        RETURN NULL;
    END IF;

    INSERT INTO tenants_history (operation, valid_from, changed_by,
                                 id, name, functional_asset, reporting_timezone, closed_through, created_at,
                                 deactivated_at)
    VALUES (TG_OP, now(), ledger_actor(),
            NEW.id, NEW.name, NEW.functional_asset, NEW.reporting_timezone, NEW.closed_through, NEW.created_at,
            NEW.deactivated_at);
    RETURN NULL;
END
$$;

-- Same as V4, plus: no postings for a deactivated tenant. The FOR SHARE lock makes deactivation wait for
-- in-flight postings, exactly like the period close.
CREATE OR REPLACE FUNCTION transactions_before_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    v_closed_through timestamptz;
    v_deactivated_at timestamptz;
BEGIN
    NEW.recorded_at  := now();
    NEW.created_by   := ledger_actor();
    NEW.effective_at := coalesce(NEW.effective_at, NEW.recorded_at);

    IF NEW.effective_at > NEW.recorded_at + interval '5 minutes' THEN
        RAISE EXCEPTION 'LEDGER_FUTURE_DATED: effective_at % is in the future', NEW.effective_at
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT closed_through, deactivated_at INTO v_closed_through, v_deactivated_at
    FROM tenants WHERE id = NEW.tenant_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'LEDGER_INVALID: unknown tenant %', NEW.tenant_id
            USING ERRCODE = 'foreign_key_violation';
    END IF;

    IF v_deactivated_at IS NOT NULL THEN
        RAISE EXCEPTION 'LEDGER_TENANT_DEACTIVATED: tenant % was deactivated at %', NEW.tenant_id, v_deactivated_at
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_closed_through IS NOT NULL AND NEW.effective_at <= v_closed_through THEN
        RAISE EXCEPTION 'LEDGER_PERIOD_CLOSED: effective_at % falls in a closed period (closed through %)',
            NEW.effective_at, v_closed_through
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.reverses_transaction_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM transactions
        WHERE id = NEW.reverses_transaction_id AND reverses_transaction_id IS NOT NULL) THEN
        RAISE EXCEPTION 'LEDGER_INVALID_REVERSAL: transaction % is itself a reversal and cannot be reversed',
            NEW.reverses_transaction_id
            USING ERRCODE = 'check_violation';
    END IF;

    UPDATE ledger_heads SET last_seq = last_seq + 1
    WHERE tenant_id = NEW.tenant_id
    RETURNING last_seq INTO NEW.seq;

    RETURN NEW;
END
$$;
