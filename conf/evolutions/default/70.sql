# --- !Ups
-- Add overpass_target_type to challenges
ALTER TABLE IF EXISTS challenges
  ADD COLUMN overpass_target_type VARCHAR;;

-- Add additional_reviewer to task_review
ALTER TABLE IF EXISTS task_review
  ADD COLUMN additional_reviewers INTEGER[];;

-- Add original_reviewer to task_review_history
ALTER TABLE IF EXISTS task_review_history
  ADD COLUMN original_reviewer INTEGER;;


-- Fix task_review_history review_started_at column
-- This column was being set with the last time this
-- task was reviewed, it's started at time when it should
-- be set with when the review was claimed at. We can fix
-- all the latest entries in the task_review_history table
-- by looking at the task_review table.
UPDATE task_review_history SET review_started_at = NULL;;

WITH last_entry AS (
  SELECT tr.*, ROW_NUMBER() OVER (PARTITION BY task_id ORDER BY id DESC) AS entry
  from task_review_history tr where review_status = 1 OR review_status = 2 or review_status = 3
), new_entry AS (
  SELECT last_entry.id as row_id, tr.review_started_at as new_started_at, tr.task_id as new_task_id
  FROM last_entry
  INNER JOIN task_review tr ON tr.task_id = last_entry.task_id
  WHERE entry = 1 and last_entry.review_status = tr.review_status
)
UPDATE task_review_history
SET review_started_at = new_started_at
FROM new_entry
WHERE id = new_entry.row_id


# --- !Downs
-- Remove added column overpass_target_type
ALTER TABLE IF EXISTS challenges
  DROP COLUMN overpass_target_type;;

-- Remove added column additional_reviewer
ALTER TABLE IF EXISTS task_review
  DROP COLUMN additional_reviewers;;

-- Remove column original_reviewer
ALTER TABLE IF EXISTS task_review_history
  DROP COLUMN original_reviewer;;
