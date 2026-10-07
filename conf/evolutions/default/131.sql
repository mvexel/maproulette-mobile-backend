# --- MapRoulette Scheme

# --- !Ups

-- Mobile admin (fork only). Additive: two new tables, nothing existing is changed.

-- Approved mobile OAuth clients. Seeded from mobileOAuth.clients on first use, see
-- docs/mobile-oauth.md "Clients" for the precedence between config and admin edits.
-- created_by NULL = seeded from config, updated_by NULL = never edited through the admin API.
CREATE TABLE mobile_oauth_clients (
    id text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9._-]{1,100}$'),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    redirect_uris text[] NOT NULL CHECK (cardinality(redirect_uris) BETWEEN 1 AND 10),
    scopes text NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    created_by bigint,
    updated_by bigint,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW()
);;

-- Append-only record of every admin write. No foreign key: entries outlive deleted users.
CREATE TABLE mobile_admin_audit (
    id bigserial PRIMARY KEY,
    actor_user_id bigint NOT NULL,
    action text NOT NULL,
    target text NOT NULL,
    before jsonb,
    after jsonb,
    created_at timestamptz NOT NULL DEFAULT NOW()
);;
CREATE INDEX mobile_admin_audit_created ON mobile_admin_audit(created_at);;

# --- !Downs

DROP TABLE mobile_admin_audit;;
DROP TABLE mobile_oauth_clients;;
