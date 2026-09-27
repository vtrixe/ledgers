CREATE TABLE assets (
    code  text     PRIMARY KEY CHECK (code ~ '^[A-Z]{3}$'),
    scale smallint NOT NULL CHECK (scale BETWEEN 0 AND 18),
    name  text     NOT NULL
);


CREATE TABLE tenants (
    id                 uuid        PRIMARY KEY DEFAULT uuidv7(),
    name               text        NOT NULL,
    functional_asset   text        NOT NULL REFERENCES assets (code),
    reporting_timezone text        NOT NULL,
    closed_through     timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_heads (
    tenant_id uuid   PRIMARY KEY REFERENCES tenants (id),
    last_seq  bigint NOT NULL DEFAULT 0 CHECK (last_seq >= 0)
);

CREATE TABLE api_keys (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id  uuid        NOT NULL REFERENCES tenants (id),
    key_hash   bytea       NOT NULL UNIQUE CHECK (octet_length(key_hash) = 32),
    hint       text        NOT NULL CHECK (length(hint) BETWEEN 1 AND 8),
    name       text        NOT NULL,
    scopes     text[]      NOT NULL CHECK (cardinality(scopes) > 0
                               AND scopes <@ ARRAY['ledger:read', 'ledger:write', 'accounts:manage']),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    CHECK (expires_at IS NULL OR expires_at > created_at)
);

CREATE UNIQUE INDEX api_keys_one_active_per_tenant ON api_keys (tenant_id) WHERE revoked_at IS NULL;


CREATE TABLE accounts (
    id             uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid        NOT NULL REFERENCES tenants (id),
    path           text        COLLATE "C" NOT NULL
                               CHECK (length(path) <= 255
                                  AND path ~ '^(assets|liabilities|equity|revenue|expenses)(:[a-z0-9_]+)+$'),
    type           text        GENERATED ALWAYS AS (split_part(path, ':', 1)) STORED,
    allow_negative boolean     NOT NULL DEFAULT false,
    metadata       jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, path)
);

CREATE TABLE account_paths (
    tenant_id  uuid        NOT NULL,
    path       text        COLLATE "C" NOT NULL,
    account_id uuid        NOT NULL,
    valid_from timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, path),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounts (tenant_id, id)
);

CREATE INDEX account_paths_account_idx ON account_paths (account_id, valid_from);


CREATE TABLE transactions (
    id                      uuid        PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid        NOT NULL REFERENCES tenants (id),
    seq                     bigint      NOT NULL,     -- set by trigger from ledger_heads
    idempotency_key         text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash            bytea       NOT NULL CHECK (octet_length(request_hash) = 32),
    effective_at            timestamptz NOT NULL,     -- defaults to recorded_at in trigger
    recorded_at             timestamptz NOT NULL,     -- always now(), set by trigger
    posting_count           integer     NOT NULL CHECK (posting_count >= 2),
    reverses_transaction_id uuid        UNIQUE,       -- a transaction can be reversed at most once
    created_by              text        NOT NULL,     -- set by trigger from ledger.actor
    metadata                jsonb       NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(metadata) = 'object'),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, seq),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, reverses_transaction_id) REFERENCES transactions (tenant_id, id),
    CHECK (reverses_transaction_id <> id)
);

CREATE TABLE postings (
    id             uuid   PRIMARY KEY DEFAULT uuidv7(),
    tenant_id      uuid   NOT NULL,
    transaction_id uuid   NOT NULL,
    account_id     uuid   NOT NULL,
    asset          text   NOT NULL REFERENCES assets (code),
    amount         bigint NOT NULL CHECK (amount <> 0 AND amount BETWEEN -9223372036854775807 AND 9223372036854775807),
    FOREIGN KEY (tenant_id, transaction_id) REFERENCES transactions (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id)     REFERENCES accounts (tenant_id, id)
);

CREATE INDEX postings_transaction_idx ON postings (transaction_id);


CREATE TABLE tenants_history (
    history_id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    operation          text        NOT NULL CHECK (operation IN ('INSERT', 'UPDATE')),
    valid_from         timestamptz NOT NULL,
    changed_by         text        NOT NULL,
    id                 uuid        NOT NULL,
    name               text        NOT NULL,
    functional_asset   text        NOT NULL,
    reporting_timezone text        NOT NULL,
    closed_through     timestamptz,
    created_at         timestamptz NOT NULL
);

CREATE INDEX tenants_history_id_idx ON tenants_history (id, valid_from);

CREATE TABLE accounts_history (
    history_id     uuid        PRIMARY KEY DEFAULT uuidv7(),
    operation      text        NOT NULL CHECK (operation IN ('INSERT', 'UPDATE')),
    valid_from     timestamptz NOT NULL,
    changed_by     text        NOT NULL,
    id             uuid        NOT NULL,
    tenant_id      uuid        NOT NULL,
    path           text        COLLATE "C" NOT NULL,
    allow_negative boolean     NOT NULL,
    metadata       jsonb       NOT NULL,
    created_at     timestamptz NOT NULL
);

CREATE INDEX accounts_history_id_idx ON accounts_history (id, valid_from);

CREATE TABLE api_keys_history (
    history_id uuid        PRIMARY KEY DEFAULT uuidv7(),
    operation  text        NOT NULL CHECK (operation IN ('INSERT', 'UPDATE')),
    valid_from timestamptz NOT NULL,
    changed_by text        NOT NULL,
    id         uuid        NOT NULL,
    tenant_id  uuid        NOT NULL,
    hint       text        NOT NULL,
    name       text        NOT NULL,
    scopes     text[]      NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz,
    revoked_at timestamptz
);

CREATE INDEX api_keys_history_id_idx ON api_keys_history (id, valid_from);
