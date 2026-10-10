# --- MapRoulette Scheme

# --- !Ups

-- Deferred sign-up (fork only), pending answers. A guest's answer to a choice task is held here
-- until the guest links an OSM account and the publish job applies it, or until it expires. Rows
-- never change a task's status. Additive: one new table.
CREATE TABLE choice_pending (
  id              bigserial PRIMARY KEY,
  guest_id        uuid REFERENCES mobile_guests(id) ON DELETE SET NULL,
  task_id         bigint NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  challenge_id    bigint NOT NULL,
  -- The canonical submission body, as for POST /task/:id/choice without delete.
  body            jsonb NOT NULL,
  -- SHA-256 of the task payload when answered. A re-uploaded payload supersedes the answer.
  payload_digest  text NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
  element_version bigint NOT NULL,
  answered_at     timestamptz NOT NULL DEFAULT now(),
  -- Discovery (excludePending) hides the task from other mappers until then.
  hold_until      timestamptz NOT NULL,
  state           text NOT NULL DEFAULT 'pending'
                  CHECK (state IN ('pending', 'published', 'skipped_stale', 'superseded', 'expired', 'failed')),
  attempts        smallint NOT NULL DEFAULT 0,
  result          jsonb,
  updated_at      timestamptz NOT NULL DEFAULT now()
);;
CREATE UNIQUE INDEX choice_pending_one_per_guest
  ON choice_pending(guest_id, task_id) WHERE state = 'pending';;
CREATE INDEX choice_pending_task ON choice_pending(task_id, hold_until) WHERE state = 'pending';;
CREATE INDEX choice_pending_guest ON choice_pending(guest_id, id);;

# --- !Downs
DROP TABLE IF EXISTS choice_pending;;
