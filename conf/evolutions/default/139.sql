# --- MapRoulette Scheme

# --- !Ups

-- Deferred sign-up (fork only), publish job. When a claim first waited on the field write
-- switch, so the job knows to send the "now on the map" email when it finishes. Additive.
ALTER TABLE mobile_claims ADD COLUMN waited_at timestamptz;;

# --- !Downs
ALTER TABLE mobile_claims DROP COLUMN IF EXISTS waited_at;;
