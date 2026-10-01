-- Sign-in moves to Auth0 Universal Login. A user is keyed on the Auth0 user id (sub), which is the same in
-- every application of the tenant, so the same person is recognised on cardbox.trading and cardbox.club.
-- Rows that predate Auth0 (and staff an owner adds by email) get their sub on first sign-in with that verified email.
ALTER TABLE users ADD COLUMN auth0_sub text;
CREATE UNIQUE INDEX users_auth0_sub ON users (auth0_sub);
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
