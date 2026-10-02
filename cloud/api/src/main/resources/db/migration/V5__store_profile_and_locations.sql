-- Store profile: contact basics an owner can edit on the Store page.
ALTER TABLE tenants ADD COLUMN website       text NOT NULL DEFAULT '';
ALTER TABLE tenants ADD COLUMN phone         text NOT NULL DEFAULT '';
ALTER TABLE tenants ADD COLUMN contact_email text NOT NULL DEFAULT '';

-- A store has one or more locations. Archived locations stay so old trades keep their tag.
CREATE TABLE locations (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants(id),
    name        text NOT NULL,
    address     text NOT NULL DEFAULT '',
    phone       text NOT NULL DEFAULT '',
    archived_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX locations_tenant_name ON locations (tenant_id, lower(name));

-- Every existing store gets one location, named "Main", which its existing trades move into.
INSERT INTO locations (id, tenant_id, name, created_at)
SELECT gen_random_uuid(), id, 'Main', created_at FROM tenants;

-- Cards come into inventory through trades, so the trade carries the location its cards went to.
ALTER TABLE trades ADD COLUMN location_id uuid REFERENCES locations(id);
UPDATE trades t SET location_id = l.id FROM locations l WHERE l.tenant_id = t.tenant_id;
ALTER TABLE trades ALTER COLUMN location_id SET NOT NULL;
CREATE INDEX trades_location_created ON trades (location_id, created_at DESC);

-- A store can have several owners. Removed people keep their row so their trades still show who took them,
-- but they can no longer sign in to the store.
ALTER TABLE users ADD COLUMN removed_at timestamptz;
