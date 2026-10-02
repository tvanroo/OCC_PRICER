-- Storage inside a location is a tree the store designs itself: each spot has a tier label the store chose
-- ("Store room", "Shelf", "Box", "Section") and a name ("Back room", "A", "12"). Children of a spot are its options.
CREATE TABLE storage_spots (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id),
    location_id uuid NOT NULL REFERENCES locations(id),
    parent_id   uuid REFERENCES storage_spots(id),
    label       text NOT NULL,
    name        text NOT NULL,
    position    integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX storage_spots_location ON storage_spots (location_id, parent_id);
-- Two siblings can't share a tier and name (two "Shelf A"s in one room).
CREATE UNIQUE INDEX storage_spots_sibling ON storage_spots (location_id, parent_id, lower(label), lower(name)) NULLS NOT DISTINCT;

-- What the store has on hand. One row per card, finish and condition in each spot; a null spot is
-- "not put away yet" at that location.
CREATE TABLE inventory_items (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants(id),
    location_id      uuid NOT NULL REFERENCES locations(id),
    storage_id       uuid REFERENCES storage_spots(id),
    card_id          uuid NOT NULL,
    name             text NOT NULL,
    set_code         text NOT NULL,
    collector_number text NOT NULL,
    rarity           text NOT NULL,
    lang             text NOT NULL,
    finish           text NOT NULL,
    condition        text NOT NULL,
    quantity         integer NOT NULL CHECK (quantity > 0),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE NULLS NOT DISTINCT (location_id, storage_id, card_id, finish, condition)
);
CREATE INDEX inventory_items_tenant ON inventory_items (tenant_id, location_id);
CREATE INDEX inventory_items_name_trgm ON inventory_items USING gin (lower(name) gin_trgm_ops);

-- Starting stock: everything already bought in, at the trade's location, not yet put away.
INSERT INTO inventory_items (id, tenant_id, location_id, card_id, name, set_code, collector_number, rarity, lang,
                             finish, condition, quantity)
SELECT gen_random_uuid(), t.tenant_id, t.location_id, l.card_id, min(l.name), min(l.set_code), min(l.collector_number),
       min(l.rarity), min(l.lang), l.finish, l.condition, sum(l.quantity)
FROM trade_lines l JOIN trades t ON t.id = l.trade_id
GROUP BY t.tenant_id, t.location_id, l.card_id, l.finish, l.condition;
