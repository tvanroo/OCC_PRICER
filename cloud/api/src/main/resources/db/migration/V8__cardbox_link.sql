-- With the CardBox link switched on, people, stores and roles live in CardBox. A Trading store row then holds the
-- business data (locations, inventory, rates, trades) for one CardBox store, found by that store's id, which never
-- changes. users rows become a copy of the CardBox roles taken at each sign-in, kept so trades still show who took them.
ALTER TABLE tenants ADD COLUMN cardbox_store_id text;
CREATE UNIQUE INDEX tenants_cardbox_store ON tenants (cardbox_store_id);

-- The signed-in person's CardBox access token, encrypted, so Trading's server can call CardBox as them.
-- platform_owner is what CardBox said at their last sign-in.
CREATE TABLE cardbox_tokens (
    auth0_sub      text PRIMARY KEY,
    token          bytea NOT NULL,
    expires_at     timestamptz NOT NULL,
    platform_owner boolean NOT NULL DEFAULT false,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
