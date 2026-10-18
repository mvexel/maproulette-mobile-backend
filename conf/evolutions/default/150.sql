# --- MapRoulette Scheme

# --- !Ups

-- Guest registration retries (fork only, G-IDEM): the app sends a random install id it reuses
-- across retries of POST /oauth/mobile/guest, so a retry whose response was lost returns the same
-- unused guest instead of leaving an empty orphan. At most one guest per client holds an install
-- id, and a newer guest takes it over.
ALTER TABLE mobile_guests ADD COLUMN IF NOT EXISTS install_id text
  CHECK (install_id ~ '^[A-Za-z0-9_-]{16,64}$');;
CREATE UNIQUE INDEX IF NOT EXISTS mobile_guests_install
  ON mobile_guests(client_id, install_id) WHERE install_id IS NOT NULL;;

# --- !Downs
DROP INDEX IF EXISTS mobile_guests_install;;
ALTER TABLE mobile_guests DROP COLUMN IF EXISTS install_id;;
