# --- MapRoulette Scheme

# --- !Ups

-- Live-filtered choice tasks (liveMissingQuestions) go stale as already_tagged once OSM has
-- every key they ask about. 130 only allowed the reasons of fixed tasks, so the insert failed
-- and such tasks stayed in discovery.
ALTER TABLE choice_stale DROP CONSTRAINT IF EXISTS choice_stale_reason_check;;
ALTER TABLE choice_stale ADD CONSTRAINT choice_stale_reason_check
  CHECK (reason IN ('element_gone', 'match_failed', 'key_changed', 'already_tagged'));;

# --- !Downs
DELETE FROM choice_stale WHERE reason = 'already_tagged';;
ALTER TABLE choice_stale DROP CONSTRAINT IF EXISTS choice_stale_reason_check;;
ALTER TABLE choice_stale ADD CONSTRAINT choice_stale_reason_check
  CHECK (reason IN ('element_gone', 'match_failed', 'key_changed'));;
