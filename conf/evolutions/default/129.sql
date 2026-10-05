# --- MapRoulette Scheme

# --- !Ups

-- Additive, isolated OAuth state. Existing sessions, API keys and user rows are unchanged.
-- Secret-bearing identifiers and credentials are stored only as SHA-256 digests.
CREATE TABLE mobile_oauth_interactions (
    interaction_hash text PRIMARY KEY CHECK (interaction_hash ~ '^[0-9a-f]{64}$'),
    browser_hash text NOT NULL CHECK (browser_hash ~ '^[0-9a-f]{64}$'),
    csrf_hash text CHECK (csrf_hash ~ '^[0-9a-f]{64}$'),
    client_id text NOT NULL,
    redirect_uri text NOT NULL,
    scope text NOT NULL,
    state text NOT NULL,
    code_challenge text NOT NULL,
    expires_at timestamptz NOT NULL,
    login_claimed_at timestamptz,
    consumed_at timestamptz,
    user_id bigint REFERENCES users(id) ON DELETE CASCADE
);;
CREATE INDEX mobile_oauth_interactions_expiry ON mobile_oauth_interactions(expires_at);;
CREATE INDEX mobile_oauth_interactions_user ON mobile_oauth_interactions(user_id);;

CREATE TABLE mobile_oauth_families (
    family_id text PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_id text NOT NULL,
    scope text NOT NULL,
    redirect_uri text NOT NULL,
    code_challenge text NOT NULL,
    created_at timestamptz NOT NULL,
    revoked_at timestamptz
);;
CREATE INDEX mobile_oauth_families_user_client ON mobile_oauth_families(user_id, client_id);;

CREATE TABLE mobile_oauth_codes (
    code_hash text PRIMARY KEY CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    family_id text NOT NULL REFERENCES mobile_oauth_families(family_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);;
CREATE INDEX mobile_oauth_codes_family ON mobile_oauth_codes(family_id);;
CREATE INDEX mobile_oauth_codes_expiry ON mobile_oauth_codes(expires_at);;

CREATE TABLE mobile_oauth_access_tokens (
    token_hash text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    family_id text NOT NULL REFERENCES mobile_oauth_families(family_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL
);;
CREATE INDEX mobile_oauth_access_family ON mobile_oauth_access_tokens(family_id);;
CREATE INDEX mobile_oauth_access_expiry ON mobile_oauth_access_tokens(expires_at);;

CREATE TABLE mobile_oauth_refresh_tokens (
    token_hash text PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    family_id text NOT NULL REFERENCES mobile_oauth_families(family_id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);;
CREATE INDEX mobile_oauth_refresh_family ON mobile_oauth_refresh_tokens(family_id);;
CREATE INDEX mobile_oauth_refresh_expiry ON mobile_oauth_refresh_tokens(expires_at);;

# --- !Downs

-- Only tables introduced by this migration are removed on explicit rollback.
DROP TABLE mobile_oauth_refresh_tokens;;
DROP TABLE mobile_oauth_access_tokens;;
DROP TABLE mobile_oauth_codes;;
DROP TABLE mobile_oauth_families;;
DROP TABLE mobile_oauth_interactions;;
