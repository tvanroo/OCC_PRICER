-- One CardBox login can belong to several stores. Each users row is now a membership: one person in one store,
-- with a role there. The same Auth0 sub (and email) may appear once per store; the session names the membership,
-- so switching stores is signing the same person into their row for another store.
DROP INDEX users_email;
DROP INDEX users_auth0_sub;
CREATE UNIQUE INDEX users_tenant_email ON users (tenant_id, lower(email));
CREATE UNIQUE INDEX users_tenant_auth0_sub ON users (tenant_id, auth0_sub);
CREATE INDEX users_auth0_sub ON users (auth0_sub);
CREATE INDEX users_email ON users (lower(email));

-- Sign-in opens the store the person used last.
ALTER TABLE users ADD COLUMN last_used_at timestamptz;
