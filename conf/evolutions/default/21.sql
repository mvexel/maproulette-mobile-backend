# --- MapRoulette Scheme

# --- !Ups
-- Add last_task_refresh column to challenges table
ALTER TABLE "challenges" ADD COLUMN last_task_refresh TIMESTAMP WITH TIME ZONE;;
UPDATE challenges c SET last_task_refresh=created WHERE last_task_refresh IS NULL AND 0 < (SELECT COUNT(*) FROM tasks t WHERE t.parent_id = c.id LIMIT 1);;

# --- !Downs
ALTER TABLE "challenges" DROP COLUMN last_task_refresh;;
