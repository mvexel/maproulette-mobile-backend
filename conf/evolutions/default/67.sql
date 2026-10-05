# --- !Ups
-- Add preferred_review_tags, limit_tags and limit_review_tags
ALTER TABLE IF EXISTS challenges
  ADD COLUMN preferred_review_tags VARCHAR,
  ADD COLUMN limit_tags BOOLEAN DEFAULT false,
  ADD COLUMN limit_review_tags BOOLEAN DEFAULT false;;

# --- !Downs
-- Remove added columns
ALTER TABLE IF EXISTS challenges
  DROP COLUMN preferred_review_tags,
  DROP COLUMN limit_tags,
  DROP COLUMN limit_review_tags;;
