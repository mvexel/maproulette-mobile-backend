# --- MapRoulette Scheme

# --- !Ups

-- "On the map" (fork only, H1c): a guest answer whose tags OSM now carries ends on_map. The
-- queue items record when each was seen on the map and when it was last checked, so the hourly
-- check reads the least recently checked first.
ALTER TABLE choice_pending DROP CONSTRAINT IF EXISTS choice_pending_state_check;;
ALTER TABLE choice_pending ADD CONSTRAINT choice_pending_state_check
  CHECK (state IN ('pending', 'published', 'skipped_stale', 'superseded', 'expired', 'failed',
                   'on_map'));;
ALTER TABLE choice_queue_items ADD COLUMN on_map_at timestamptz;;
ALTER TABLE choice_queue_items ADD COLUMN checked_at timestamptz;;
CREATE INDEX choice_queue_items_unchecked ON choice_queue_items(checked_at NULLS FIRST)
  WHERE on_map_at IS NULL;;

# --- !Downs
DROP INDEX IF EXISTS choice_queue_items_unchecked;;
ALTER TABLE choice_queue_items DROP COLUMN IF EXISTS checked_at;;
ALTER TABLE choice_queue_items DROP COLUMN IF EXISTS on_map_at;;
UPDATE choice_pending SET state = 'published' WHERE state = 'on_map';;
ALTER TABLE choice_pending DROP CONSTRAINT IF EXISTS choice_pending_state_check;;
ALTER TABLE choice_pending ADD CONSTRAINT choice_pending_state_check
  CHECK (state IN ('pending', 'published', 'skipped_stale', 'superseded', 'expired', 'failed'));;
