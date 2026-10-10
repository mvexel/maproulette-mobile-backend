# --- MapRoulette Scheme

# --- !Ups

-- Deferred sign-up (fork only), claims. A guest's pending answers are claimed by an OSM account
-- through the claim page; the publish job then applies them. Additive: one new table and one
-- nullable column on the fork's choice_pending.
CREATE TABLE mobile_claims (
  id          bigserial PRIMARY KEY,
  guest_id    uuid REFERENCES mobile_guests(id) ON DELETE SET NULL,
  user_id     bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- The phone's grant family created by the claim; it holds the copy of the OSM token the
  -- publish job writes with.
  family_id   text NOT NULL,
  state       text NOT NULL DEFAULT 'queued'
              CHECK (state IN ('queued', 'waiting', 'running', 'done', 'failed')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz
);;
-- One claim per guest.
CREATE UNIQUE INDEX mobile_claims_guest ON mobile_claims(guest_id) WHERE guest_id IS NOT NULL;;
CREATE INDEX mobile_claims_open ON mobile_claims(id) WHERE state IN ('queued', 'waiting', 'running');;

ALTER TABLE choice_pending ADD COLUMN claim_id bigint REFERENCES mobile_claims(id) ON DELETE SET NULL;;
CREATE INDEX choice_pending_claim ON choice_pending(claim_id) WHERE claim_id IS NOT NULL;;

# --- !Downs
ALTER TABLE choice_pending DROP COLUMN IF EXISTS claim_id;;
DROP TABLE IF EXISTS mobile_claims;;
