CREATE FUNCTION ledger_actor() RETURNS text
LANGUAGE sql STABLE AS $$
    SELECT coalesce(nullif(current_setting('ledger.actor', true), ''), session_user::text)
$$;

CREATE FUNCTION reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'LEDGER_IMMUTABLE: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END
$$;

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['transactions', 'postings', 'account_paths',
                             'tenants_history', 'accounts_history', 'api_keys_history'] LOOP
        EXECUTE format('CREATE TRIGGER %1$s_append_only BEFORE UPDATE OR DELETE ON %1$I
                        FOR EACH ROW EXECUTE FUNCTION reject_modification()', t);
    END LOOP;

    FOREACH t IN ARRAY ARRAY['assets', 'tenants', 'ledger_heads', 'api_keys', 'accounts'] LOOP
        EXECUTE format('CREATE TRIGGER %1$s_no_delete BEFORE DELETE ON %1$I
                        FOR EACH ROW EXECUTE FUNCTION reject_modification()', t);
    END LOOP;

    FOREACH t IN ARRAY ARRAY['assets', 'tenants', 'ledger_heads', 'api_keys', 'accounts', 'account_paths',
                             'transactions', 'postings',
                             'tenants_history', 'accounts_history', 'api_keys_history'] LOOP
        EXECUTE format('CREATE TRIGGER %1$s_no_truncate BEFORE TRUNCATE ON %1$I
                        FOR EACH STATEMENT EXECUTE FUNCTION reject_modification()', t);
    END LOOP;
END
$$;
