-- Transaction and posting invariants.
--   At INSERT:  recorded_at/created_by/seq are set by the database, no future dating, period lock, no reversal of a reversal.
--   At COMMIT:  posting count matches the header, at least two accounts, balanced per asset, reversals mirror the original.

CREATE FUNCTION transactions_before_insert() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
DECLARE
    v_closed_through timestamptz;
BEGIN
    -- Values the client must not choose; anything supplied is overwritten.
    NEW.recorded_at  := now();
    NEW.created_by   := ledger_actor();
    NEW.effective_at := coalesce(NEW.effective_at, NEW.recorded_at);

    IF NEW.effective_at > NEW.recorded_at + interval '5 minutes' THEN
        RAISE EXCEPTION 'LEDGER_FUTURE_DATED: effective_at % is in the future', NEW.effective_at
            USING ERRCODE = 'check_violation';
    END IF;


    SELECT closed_through INTO v_closed_through FROM tenants WHERE id = NEW.tenant_id FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'LEDGER_INVALID: unknown tenant %', NEW.tenant_id
            USING ERRCODE = 'foreign_key_violation';
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

CREATE TRIGGER transactions_before_insert BEFORE INSERT ON transactions
    FOR EACH ROW EXECUTE FUNCTION transactions_before_insert();

CREATE FUNCTION assert_transaction_valid(p_transaction_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    v_tx         transactions%ROWTYPE;
    v_count      integer;
    v_accounts   integer;
    v_unbalanced text;
BEGIN
    SELECT * INTO STRICT v_tx FROM transactions WHERE id = p_transaction_id;

    SELECT count(*), count(DISTINCT account_id) INTO v_count, v_accounts
    FROM postings WHERE transaction_id = p_transaction_id;

    -- Also catches a header with no postings, and postings added to an already committed transaction.
    IF v_count <> v_tx.posting_count THEN
        RAISE EXCEPTION 'LEDGER_POSTING_COUNT: transaction % declares % postings but has %',
            p_transaction_id, v_tx.posting_count, v_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF v_accounts < 2 THEN
        RAISE EXCEPTION 'LEDGER_SINGLE_ACCOUNT: transaction % must touch at least two accounts', p_transaction_id
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT string_agg(format('%s %s', asset, total), ', ' ORDER BY asset) INTO v_unbalanced
    FROM (SELECT asset, sum(amount) AS total
          FROM postings WHERE transaction_id = p_transaction_id
          GROUP BY asset
          HAVING sum(amount) <> 0) unbalanced;

    IF v_unbalanced IS NOT NULL THEN
        RAISE EXCEPTION 'LEDGER_UNBALANCED: transaction % does not balance per asset (off by %)',
            p_transaction_id, v_unbalanced
            USING ERRCODE = 'check_violation';
    END IF;

    -- A reversal is the exact mirror of the original: same accounts and assets, negated amounts.
    IF v_tx.reverses_transaction_id IS NOT NULL AND EXISTS (
        (SELECT account_id, asset, amount FROM postings WHERE transaction_id = p_transaction_id
         EXCEPT ALL
         SELECT account_id, asset, -amount FROM postings WHERE transaction_id = v_tx.reverses_transaction_id)
        UNION ALL
        (SELECT account_id, asset, -amount FROM postings WHERE transaction_id = v_tx.reverses_transaction_id
         EXCEPT ALL
         SELECT account_id, asset, amount FROM postings WHERE transaction_id = p_transaction_id)) THEN
        RAISE EXCEPTION 'LEDGER_INVALID_REVERSAL: postings of % are not an exact mirror of %',
            p_transaction_id, v_tx.reverses_transaction_id
            USING ERRCODE = 'check_violation';
    END IF;
END
$$;

CREATE FUNCTION transactions_check_at_commit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_transaction_valid(NEW.id);
    RETURN NULL;
END
$$;

CREATE FUNCTION postings_check_at_commit() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM assert_transaction_valid(NEW.transaction_id);
    RETURN NULL;
END
$$;

-- Deferred to COMMIT: a transaction is legitimately unbalanced between its individual posting inserts.
-- The header trigger catches a transaction with zero postings; the postings trigger catches late inserts.
CREATE CONSTRAINT TRIGGER transactions_valid_at_commit
    AFTER INSERT ON transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION transactions_check_at_commit();

CREATE CONSTRAINT TRIGGER postings_valid_at_commit
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION postings_check_at_commit();
