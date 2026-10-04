-- Exchange rates: global reference data, append-only (a rate is a fact about a moment in time).
CREATE TABLE fx_rates (
    id          uuid            PRIMARY KEY DEFAULT uuidv7(),
    base        text            NOT NULL REFERENCES assets (code),
    quote       text            NOT NULL REFERENCES assets (code),
    rate        numeric(24, 12) NOT NULL CHECK (rate > 0),     -- 1 base = rate quote
    as_of       timestamptz     NOT NULL,
    source      text            NOT NULL CHECK (length(source) BETWEEN 1 AND 50),
    recorded_at timestamptz     NOT NULL DEFAULT now(),
    CHECK (base <> quote),
    UNIQUE (base, quote, as_of, source)
);

CREATE INDEX fx_rates_lookup_idx ON fx_rates (base, quote, as_of DESC);

CREATE TRIGGER fx_rates_append_only BEFORE UPDATE OR DELETE ON fx_rates
    FOR EACH ROW EXECUTE FUNCTION reject_modification();
CREATE TRIGGER fx_rates_no_truncate BEFORE TRUNCATE ON fx_rates
    FOR EACH STATEMENT EXECUTE FUNCTION reject_modification();

GRANT SELECT ON fx_rates TO ledger_app;
