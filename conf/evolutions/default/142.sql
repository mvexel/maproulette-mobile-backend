# --- MapRoulette Scheme

# --- !Ups

-- Review page (fork only, H1a): mappers read a campaign's guest answers by its live page token and
-- mark places. Skips are per mapper, and "needs a visit" is shared. Additive: two new tables and an
-- index on the fork's choice_pending.
CREATE TABLE choice_review_marks (
  task_id    bigint NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  user_id    bigint NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind       text NOT NULL CHECK (kind IN ('skip')),
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (task_id, user_id, kind)
);;
CREATE TABLE choice_review_places (
  task_id      bigint PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,
  challenge_id bigint NOT NULL REFERENCES challenges(id) ON DELETE CASCADE,
  -- done: OSM carries the suggested tags (set by H1c).
  state        text NOT NULL DEFAULT 'open' CHECK (state IN ('open', 'needs_visit', 'done')),
  marked_by    bigint REFERENCES users(id) ON DELETE SET NULL,
  marked_at    timestamptz NOT NULL DEFAULT now()
);;
CREATE INDEX choice_review_places_challenge ON choice_review_places(challenge_id, state);;
CREATE INDEX choice_pending_challenge ON choice_pending(challenge_id, task_id) WHERE state = 'pending';;

# --- !Downs
DROP INDEX IF EXISTS choice_pending_challenge;;
DROP TABLE IF EXISTS choice_review_places;;
DROP TABLE IF EXISTS choice_review_marks;;
