# --- MapRoulette Scheme

# --- !Ups

-- Mobile guests (fork only, deferred sign-up). Additive: three new tables, nothing existing is
-- changed. A guest answers choice tasks before having an OSM account and is not a users row
-- (users.osm_id is NOT NULL). See docs/mobile-oauth.md "Guests".

-- Credentials are SHA-256 hex digests, as in evolution 129. secret_hash is NULL once the guest
-- secret is spent (claimed and exchanged) or the guest deleted its data.
CREATE TABLE mobile_guests (
    id uuid PRIMARY KEY,
    client_id text NOT NULL REFERENCES mobile_oauth_clients(id),
    secret_hash text UNIQUE CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT NOW(),
    last_seen_at timestamptz,
    expires_at timestamptz NOT NULL,
    -- AES-256-GCM under mobileOAuth.osmTokenKey with the guest id as associated data.
    email_ciphertext bytea,
    email_nonce bytea,
    email_set_at timestamptz,
    email_verified_at timestamptz,
    reminded_1_at timestamptz,
    reminded_2_at timestamptz,
    reminders_stopped_at timestamptz,
    claimed_user_id bigint REFERENCES users(id),
    claimed_at timestamptz,
    phone_family_id text REFERENCES mobile_oauth_families(family_id) ON DELETE SET NULL,
    exchanged_at timestamptz,
    deleted_at timestamptz,
    CHECK ((email_ciphertext IS NULL) = (email_nonce IS NULL))
);;
CREATE INDEX mobile_guests_expires ON mobile_guests(expires_at) WHERE deleted_at IS NULL;;

-- Short-lived guest access tokens (scope "guest"). No refresh tokens: the guest secret mints them.
CREATE TABLE mobile_guest_tokens (
    token_hash text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    guest_id uuid NOT NULL REFERENCES mobile_guests(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);;
CREATE INDEX mobile_guest_tokens_guest ON mobile_guest_tokens(guest_id);;

-- Tokens in emailed claim links. Kept after use or deletion so an old link can say why it no
-- longer works.
CREATE TABLE mobile_guest_claim_tokens (
    token_hash text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    guest_id uuid NOT NULL REFERENCES mobile_guests(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    consumed_at timestamptz
);;
CREATE INDEX mobile_guest_claim_tokens_guest ON mobile_guest_claim_tokens(guest_id);;

# --- !Downs

DROP TABLE mobile_guest_claim_tokens;;
DROP TABLE mobile_guest_tokens;;
DROP TABLE mobile_guests;;
