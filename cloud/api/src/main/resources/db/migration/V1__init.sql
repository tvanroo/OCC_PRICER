CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Shared card catalog, refreshed nightly from Scryfall bulk data. Not tenant data.
CREATE TABLE cards (
    id               uuid PRIMARY KEY,
    name             text NOT NULL,
    set_code         text NOT NULL,
    set_name         text NOT NULL,
    collector_number text NOT NULL,
    rarity           text NOT NULL,
    lang             text NOT NULL,
    released_at      date,
    usd              numeric(12,2),
    usd_foil         numeric(12,2),
    usd_etched       numeric(12,2),
    image_small      text,
    updated_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX cards_name_trgm ON cards USING gin (lower(name) gin_trgm_ops);
CREATE INDEX cards_set_number ON cards (set_code, collector_number);

CREATE TABLE catalog_imports (
    id          bigserial PRIMARY KEY,
    started_at  timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    cards       integer,
    source      text,
    error       text
);

-- Tenants are stores. Every row below this line carries tenant_id.
CREATE TABLE tenants (
    id            uuid PRIMARY KEY,
    name          text NOT NULL,
    plan_status   text NOT NULL DEFAULT 'trial' CHECK (plan_status IN ('trial', 'active', 'past_due', 'canceled')),
    trial_ends_at timestamptz NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants(id),
    email         text NOT NULL,
    name          text NOT NULL,
    password_hash text NOT NULL,
    role          text NOT NULL CHECK (role IN ('owner', 'staff')),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email ON users (lower(email));
CREATE INDEX users_tenant ON users (tenant_id);

CREATE TABLE buy_rate_rules (
    tenant_id     uuid NOT NULL REFERENCES tenants(id),
    threshold_min numeric(12,2) NOT NULL CHECK (threshold_min >= 0),
    credit_rate   numeric(6,4) NOT NULL CHECK (credit_rate > 0 AND credit_rate <= 1),
    check_rate    numeric(6,4) NOT NULL CHECK (check_rate > 0 AND check_rate <= 1),
    PRIMARY KEY (tenant_id, threshold_min)
);

-- Customers are identified by phone number for now; real customer accounts come later.
CREATE TABLE customers (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants(id),
    phone      text NOT NULL,
    name       text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, phone)
);

CREATE TABLE trades (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants(id),
    number       bigint NOT NULL,
    customer_id  uuid REFERENCES customers(id),
    created_by   uuid NOT NULL REFERENCES users(id),
    payment      text NOT NULL CHECK (payment IN ('credit', 'check', 'partial')),
    credit_total numeric(12,2) NOT NULL,
    check_total  numeric(12,2) NOT NULL,
    market_total numeric(12,2) NOT NULL,
    check_number text NOT NULL DEFAULT '',
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, number)
);
CREATE INDEX trades_tenant_created ON trades (tenant_id, created_at DESC);

CREATE TABLE trade_lines (
    trade_id         uuid NOT NULL REFERENCES trades(id) ON DELETE CASCADE,
    line_no          integer NOT NULL,
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    card_id          uuid NOT NULL,
    name             text NOT NULL,
    set_code         text NOT NULL,
    collector_number text NOT NULL,
    rarity           text NOT NULL,
    lang             text NOT NULL,
    finish           text NOT NULL,
    condition        text NOT NULL,
    quantity         integer NOT NULL CHECK (quantity > 0),
    market_unit      numeric(12,2) NOT NULL,
    valuation_unit   numeric(12,2) NOT NULL,
    credit_rate      numeric(6,4) NOT NULL,
    check_rate       numeric(6,4) NOT NULL,
    credit_alloc     numeric(12,2) NOT NULL,
    check_alloc      numeric(12,2) NOT NULL,
    PRIMARY KEY (trade_id, line_no)
);
