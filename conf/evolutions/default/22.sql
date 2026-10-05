# --- MapRoulette Scheme

# --- !Ups
-- Add popularity column to challenges
ALTER TABLE "challenges" ADD COLUMN popularity INT DEFAULT FLOOR(EXTRACT(epoch from NOW()));;

-- Calculate initial popularity scores
-- Very basic scoring for popularity p: p = (p + t) / 2 where t is timestamp
-- Initial score set to created timestamp of challenge
UPDATE challenges SET popularity=FLOOR(EXTRACT(epoch from created));;

DO $$ DECLARE completion_action RECORD;;
BEGIN
  FOR completion_action IN SELECT challenge_id, FLOOR(EXTRACT(epoch FROM created)) AS createdts FROM status_actions
                           WHERE old_status != status AND status > 0 AND status < 8 ORDER BY created ASC
  LOOP
    UPDATE challenges SET popularity = ((popularity + completion_action.createdts) / 2)
    WHERE id = completion_action.challenge_id;;
  END LOOP;;
END$$;;

SELECT create_index_if_not_exists('challenges', 'popularity', '(popularity)');;

# --- !Downs
ALTER TABLE "challenges" DROP COLUMN popularity;;
