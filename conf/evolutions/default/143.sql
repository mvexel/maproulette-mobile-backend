# --- MapRoulette Scheme

# --- !Ups

-- Confirm queue (fork only, H1b): guest tag answers that pass the stale check become tasks of a
-- disabled tag-fix challenge per campaign, which mappers work on maproulette.org from its export.
-- Review places can now be stale or gone after the check. Additive: two new tables and a wider
-- check on the fork's choice_review_places.
ALTER TABLE choice_review_places DROP CONSTRAINT IF EXISTS choice_review_places_state_check;;
ALTER TABLE choice_review_places ADD CONSTRAINT choice_review_places_state_check
  CHECK (state IN ('open', 'needs_visit', 'done', 'stale', 'gone'));;
CREATE TABLE choice_queues (
  campaign_id        bigint PRIMARY KEY REFERENCES challenges(id) ON DELETE CASCADE,
  queue_challenge_id bigint NOT NULL UNIQUE REFERENCES challenges(id) ON DELETE CASCADE,
  created_at         timestamptz NOT NULL DEFAULT now()
);;
-- One queued edit per campaign task and question: reruns add only new ones.
CREATE TABLE choice_queue_items (
  queue_task_id  bigint PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
  source_task_id bigint NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  question       text NOT NULL,
  answer         text NOT NULL,
  people         int NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  UNIQUE (source_task_id, question)
);;

# --- !Downs
-- Queued answers end their far-future hold, so their tasks show in discovery again.
UPDATE choice_pending SET hold_until = now(), updated_at = now()
  WHERE state = 'pending' AND hold_until = '9999-12-31T00:00:00Z';;
DROP TABLE IF EXISTS choice_queue_items;;
DROP TABLE IF EXISTS choice_queues;;
UPDATE choice_review_places SET state = 'open' WHERE state IN ('stale', 'gone');;
ALTER TABLE choice_review_places DROP CONSTRAINT IF EXISTS choice_review_places_state_check;;
ALTER TABLE choice_review_places ADD CONSTRAINT choice_review_places_state_check
  CHECK (state IN ('open', 'needs_visit', 'done'));;
