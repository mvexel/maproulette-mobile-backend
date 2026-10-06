# --- MapRoulette Scheme

# --- !Ups

-- Mobile choice challenges (fork only). Additive: new tables plus nullable columns on the
-- mobile-only interaction table. users.oauth_token and existing task rows are never touched.

-- OSM token captured during a mobile login that requested osm:tagfix. AES-GCM ciphertext only.
-- moved to mobile_osm_tokens when the user approves, cleared when the interaction is consumed.
ALTER TABLE mobile_oauth_interactions ADD COLUMN osm_token_ciphertext bytea;;
ALTER TABLE mobile_oauth_interactions ADD COLUMN osm_token_nonce bytea;;
ALTER TABLE mobile_oauth_interactions ADD COLUMN osm_scope text;;

CREATE TABLE mobile_osm_tokens (
    grant_family_id text PRIMARY KEY REFERENCES mobile_oauth_families(family_id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    ciphertext bytea NOT NULL,
    nonce bytea NOT NULL,
    osm_scope text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT NOW()
);;
CREATE INDEX mobile_osm_tokens_user ON mobile_osm_tokens(user_id);;

-- Idempotency for POST /task/:id/choice. At most one unfinished submission per task.
CREATE TABLE mobile_choice_submissions (
    task_id bigint NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    submission_key text NOT NULL CHECK (submission_key ~ '^[0-9a-f]{64}$'),
    state text NOT NULL CHECK (state IN ('started', 'uploaded', 'done')),
    changeset_id bigint,
    result_json jsonb,
    created_at timestamptz NOT NULL DEFAULT NOW(),
    updated_at timestamptz NOT NULL DEFAULT NOW(),
    -- Set while a request works on a started row. Another request may resume it only after.
    lease_until timestamptz,
    attempt text,
    PRIMARY KEY (task_id, user_id, submission_key)
);;
CREATE UNIQUE INDEX mobile_choice_submissions_pending
    ON mobile_choice_submissions(task_id) WHERE state <> 'done';;

-- System-observed staleness: the OSM element no longer matches the payload (gone, match failed
-- or a guarded key changed). Insert-only. A re-upload that replaces the payload deletes the row
-- (payload_md5 tells). Mobile discovery leaves these tasks out with excludeStale=true. No task
-- status is written, because no user resolved the task.
CREATE TABLE choice_stale (
    task_id bigint PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
    reason text NOT NULL CHECK (reason IN ('element_gone', 'match_failed', 'key_changed')),
    detail jsonb,
    element_version bigint,
    payload_md5 text NOT NULL,
    observed_at timestamptz NOT NULL DEFAULT NOW()
);;

# --- !Downs

DROP TABLE IF EXISTS choice_stale;;
DROP TABLE IF EXISTS mobile_choice_submissions;;
DROP TABLE IF EXISTS mobile_osm_tokens;;
ALTER TABLE mobile_oauth_interactions DROP COLUMN IF EXISTS osm_scope;;
ALTER TABLE mobile_oauth_interactions DROP COLUMN IF EXISTS osm_token_nonce;;
ALTER TABLE mobile_oauth_interactions DROP COLUMN IF EXISTS osm_token_ciphertext;;
