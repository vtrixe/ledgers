-- Balances as of a point in time filter transactions by tenant and effective_at.
CREATE INDEX transactions_tenant_effective_idx ON transactions (tenant_id, effective_at);
