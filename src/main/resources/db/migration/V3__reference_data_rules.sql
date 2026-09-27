CREATE FUNCTION assets_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.code <> OLD.code OR NEW.scale <> OLD.scale THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: code and scale of asset % can never change; create a new asset instead', OLD.code
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER assets_before_update BEFORE UPDATE ON assets
    FOR EACH ROW EXECUTE FUNCTION assets_before_update();


CREATE FUNCTION tenants_before_write() RETURNS trigger
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
        RETURN NEW;
    END IF;

    IF NEW.id <> OLD.id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: tenant id and created_at can never change'
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

CREATE TRIGGER tenants_before_write BEFORE INSERT OR UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION tenants_before_write();

CREATE FUNCTION tenants_after_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO ledger_heads (tenant_id) VALUES (NEW.id);
    RETURN NULL;
END
$$;

CREATE TRIGGER tenants_after_insert AFTER INSERT ON tenants
    FOR EACH ROW EXECUTE FUNCTION tenants_after_insert();

-- Ledger heads: the sequence only ever moves forward by exactly one.
CREATE FUNCTION ledger_heads_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.tenant_id <> OLD.tenant_id OR NEW.last_seq <> OLD.last_seq + 1 THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: ledger head of tenant % can only advance by one', OLD.tenant_id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ledger_heads_before_update BEFORE UPDATE ON ledger_heads
    FOR EACH ROW EXECUTE FUNCTION ledger_heads_before_update();

-- Accounts: path is renamable, but its first segment (the account type) is not.
CREATE FUNCTION accounts_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: account id, tenant_id and created_at can never change'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF split_part(NEW.path, ':', 1) <> split_part(OLD.path, ':', 1) THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: renaming % to % would change the account type', OLD.path, NEW.path
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER accounts_before_update BEFORE UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_before_update();

-- API keys: only name, expires_at and revocation change; a revoked key stays revoked.
CREATE FUNCTION api_keys_before_update() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.key_hash <> OLD.key_hash
       OR NEW.hint <> OLD.hint OR NEW.scopes <> OLD.scopes OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: an API key''s identity, hash and scopes can never change; issue a new key'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.revoked_at IS NOT NULL AND NEW.revoked_at IS DISTINCT FROM OLD.revoked_at THEN
        RAISE EXCEPTION 'LEDGER_IMMUTABLE: API key % is revoked and stays revoked', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER api_keys_before_update BEFORE UPDATE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION api_keys_before_update();
