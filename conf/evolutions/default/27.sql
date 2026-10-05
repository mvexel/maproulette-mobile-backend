# --- MapRoulette Scheme

# --- !Ups
-- Add needs_review, is_reviewer to user table
ALTER TABLE "users" ADD COLUMN needs_review boolean DEFAULT false;;
ALTER TABLE "users" ADD COLUMN is_reviewer boolean DEFAULT false;;

-- Add table to keep track of review status
CREATE TABLE IF NOT EXISTS task_review
(
  id SERIAL NOT NULL PRIMARY KEY,
  task_id integer NOT NULL,
  review_status integer,
  review_requested_by integer,
  reviewed_by integer DEFAULT NULL,
  reviewed_at timestamp without time zone DEFAULT NULL,
  review_claimed_at timestamp without time zone DEFAULT NULL,
  review_claimed_by integer DEFAULT NULL
);;


-- Add table to keep track of review history
CREATE TABLE IF NOT EXISTS task_review_history
(
  id SERIAL NOT NULL PRIMARY KEY,
  task_id integer NOT NULL,
  requested_by integer NOT NULL,
  reviewed_by integer,
  review_status integer NOT NULL,
  reviewed_at timestamp without time zone DEFAULT NOW()
);;

ALTER TABLE "status_actions" ADD COLUMN started_at timestamp without time zone DEFAULT NULL;;

SELECT create_index_if_not_exists('task_review_history', 'task_review_history_task_id', '(task_id)');;
SELECT create_index_if_not_exists('task_review', 'tasks_review_id', '(task_id)');;
SELECT create_index_if_not_exists('task_review', 'tasks_review_status', '(review_status)');;
SELECT create_index_if_not_exists('task_review', 'tasks_reviewed_by', '(reviewed_by)');;
SELECT create_index_if_not_exists('task_review', 'tasks_review_claimed_by', '(review_claimed_by)');;
SELECT create_index_if_not_exists('task_review', 'tasks_review_claimed_at', '(review_claimed_at)');;

# --- !Downs
ALTER TABLE "users" DROP COLUMN needs_review;;
ALTER TABLE "users" DROP COLUMN is_reviewer;;

ALTER TABLE "status_actions" DROP COLUMN started_at;;

DROP TABLE IF EXISTS task_review;;
DROP TABLE IF EXISTS task_review_history;;
