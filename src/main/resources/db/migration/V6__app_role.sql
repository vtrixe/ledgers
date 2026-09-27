-- Least-privilege role for the application. Flyway (the table owner) runs migrations; the app should connect
-- as a login role that is a member of ledger_app, never as the owner. Triggers (V2-V5) still apply to every role.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledger_app') THEN
        CREATE ROLE ledger_app NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ledger_app;

-- Read-only: reference data, credentials (managed by the admin CLI), and all history.
GRANT SELECT ON assets, tenants, api_keys, account_paths,
                tenants_history, accounts_history, api_keys_history TO ledger_app;

-- Append-only ledger.
GRANT SELECT, INSERT ON transactions, postings TO ledger_app;

-- Accounts: create, and change only the updatable columns.
GRANT SELECT, INSERT ON accounts TO ledger_app;
GRANT UPDATE (path, allow_negative, metadata) ON accounts TO ledger_app;

-- ledger_heads: no direct access; only the SECURITY DEFINER transaction trigger touches it.
