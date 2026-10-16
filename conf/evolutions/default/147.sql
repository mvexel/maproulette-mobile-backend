# --- MapRoulette Scheme

# --- !Ups

-- Stale queued edits (fork only, H1d): the hourly check records when OSM stopped matching a queued
-- edit's question (the key changed after queueing). A stale edit is not read again and is left out
-- of later exports.
ALTER TABLE choice_queue_items ADD COLUMN stale_at timestamptz;;
DROP INDEX IF EXISTS choice_queue_items_unchecked;;
CREATE INDEX choice_queue_items_unchecked ON choice_queue_items(checked_at NULLS FIRST)
  WHERE on_map_at IS NULL AND stale_at IS NULL;;

# --- !Downs
DROP INDEX IF EXISTS choice_queue_items_unchecked;;
CREATE INDEX choice_queue_items_unchecked ON choice_queue_items(checked_at NULLS FIRST)
  WHERE on_map_at IS NULL;;
ALTER TABLE choice_queue_items DROP COLUMN IF EXISTS stale_at;;
