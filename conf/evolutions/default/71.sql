# --- !Ups
-- Add spatial index to task location, if missing
CREATE INDEX IF NOT EXISTS idx_tasks_location ON tasks USING GIST (location);;

# --- !Downs
DROP INDEX IF EXISTS idx_tasks_location;;
