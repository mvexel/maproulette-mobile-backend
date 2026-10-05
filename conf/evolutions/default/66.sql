# --- !Ups
-- Remove negative timestamps. Prior to PR #728 if the database and server
-- ran on different machines and had different times than this could occur
UPDATE TASKS SET completed_time_spent = NULL WHERE completed_time_spent <= 0;;

-- Add completed_tasks and avg_time_spent to user_leaderboard
ALTER TABLE IF EXISTS user_leaderboard
  ADD COLUMN completed_tasks INTEGER,
  ADD COLUMN avg_time_spent BIGINT;;

# --- !Downs

-- Remove completed_tasks and avg_time_spent from user_leaderboard
ALTER TABLE IF EXISTS user_leaderboard DROP COLUMN completed_tasks, DROP COLUMN avg_time_spent;;
