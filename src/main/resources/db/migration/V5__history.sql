-- Edit log for the mutable tables (Companies (Accounts) Rules 3(1): every change recorded, cannot be disabled).
-- Written by triggers so no code path can skip it. SECURITY DEFINER because the app role cannot write history directly.

CREATE FUNCTION tenants_record_history() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD IS NOT DISTINCT FROM NEW THEN
        RETURN NULL;
    END IF;

    INSERT INTO tenants_history (operation, valid_from, changed_by,
                                 id, name, functional_asset, reporting_timezone, closed_through, created_at)
    VALUES (TG_OP, now(), ledger_actor(),
            NEW.id, NEW.name, NEW.functional_asset, NEW.reporting_timezone, NEW.closed_through, NEW.created_at);
    RETURN NULL;
END
$$;

CREATE TRIGGER tenants_record_history AFTER INSERT OR UPDATE ON tenants
    FOR EACH ROW EXECUTE FUNCTION tenants_record_history();

CREATE FUNCTION accounts_record_history() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD IS NOT DISTINCT FROM NEW THEN
        RETURN NULL;
    END IF;

    IF TG_OP = 'INSERT' OR NEW.path <> OLD.path THEN
        -- The primary key on account_paths enforces this too; the check gives a clearer error.
        IF EXISTS (SELECT 1 FROM account_paths WHERE tenant_id = NEW.tenant_id AND path = NEW.path) THEN
            RAISE EXCEPTION 'LEDGER_PATH_RETIRED: path % was used before in this tenant and can never be reused', NEW.path
                USING ERRCODE = 'unique_violation';
        END IF;

        INSERT INTO account_paths (tenant_id, path, account_id, valid_from)
        VALUES (NEW.tenant_id, NEW.path, NEW.id, now());
    END IF;

    INSERT INTO accounts_history (operation, valid_from, changed_by,
                                  id, tenant_id, path, allow_negative, metadata, created_at)
    VALUES (TG_OP, now(), ledger_actor(),
            NEW.id, NEW.tenant_id, NEW.path, NEW.allow_negative, NEW.metadata, NEW.created_at);
    RETURN NULL;
END
$$;

CREATE TRIGGER accounts_record_history AFTER INSERT OR UPDATE ON accounts
    FOR EACH ROW EXECUTE FUNCTION accounts_record_history();

CREATE FUNCTION api_keys_record_history() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD IS NOT DISTINCT FROM NEW THEN
        RETURN NULL;
    END IF;

    INSERT INTO api_keys_history (operation, valid_from, changed_by,
                                  id, tenant_id, hint, name, scopes, created_at, expires_at, revoked_at)
    VALUES (TG_OP, now(), ledger_actor(),
            NEW.id, NEW.tenant_id, NEW.hint, NEW.name, NEW.scopes, NEW.created_at, NEW.expires_at, NEW.revoked_at);
    RETURN NULL;
END
$$;

CREATE TRIGGER api_keys_record_history AFTER INSERT OR UPDATE ON api_keys
    FOR EACH ROW EXECUTE FUNCTION api_keys_record_history();
