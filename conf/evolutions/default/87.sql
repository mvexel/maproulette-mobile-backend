# --- !Ups
CREATE INDEX IF NOT EXISTS idx_challenges_is_archived ON challenges using btree (is_archived);;

# --- !Downs
DROP INDEX IF EXISTS idx_challenges_is_archived;;