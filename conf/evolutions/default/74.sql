# --- !Ups
-- Add meta_review_status and meta_reviewed_by to task_review
ALTER TABLE task_review ADD COLUMN meta_review_started_at timestamp without time zone DEFAULT NULL;;
ALTER TABLE task_review ADD COLUMN meta_review_status INTEGER;;
ALTER TABLE task_review ADD COLUMN meta_reviewed_by INTEGER;;
ALTER TABLE task_review ADD COLUMN meta_reviewed_at timestamp without time zone DEFAULT NULL;;

-- Add additional_reviewer to task_review_history
-- We also need to make review_status nullable as there can be
-- task_review_history entries for meta_review_status where
-- review_status does not change so should not be recorded.
ALTER TABLE task_review_history ADD COLUMN meta_review_status INTEGER;;
ALTER TABLE task_review_history ADD COLUMN meta_reviewed_by INTEGER;;
ALTER TABLE task_review_history ADD COLUMN meta_reviewed_at timestamp without time zone DEFAULT NULL;;
ALTER TABLE task_review_history ALTER COLUMN review_status DROP NOT NULL;;
ALTER TABLE task_review_history ALTER COLUMN reviewed_at SET DEFAULT NULL;;

-- Add subscription options for team and follow notifications
ALTER TABLE IF EXISTS user_notification_subscriptions ADD COLUMN meta_review INTEGER NOT NULL DEFAULT 1;;


# --- !Downs
-- Drop meta_review_status and meta_reviewed_by to task_review
ALTER TABLE task_review DROP COLUMN meta_review_started_at;;
ALTER TABLE task_review DROP COLUMN meta_review_status;;
ALTER TABLE task_review DROP COLUMN meta_reviewed_by;;
ALTER TABLE task_review DROP COLUMN meta_reviewed_at;;

-- Drop additional_reviewer to task_review_history
ALTER TABLE task_review_history DROP COLUMN meta_review_status;;
ALTER TABLE task_review_history DROP COLUMN meta_reviewed_by;;
ALTER TABLE task_review_history DROP COLUMN meta_reviewed_at;;

UPDATE task_review_history SET review_status = -2 WHERE review_status IS NULL;;
ALTER TABLE task_review_history ALTER COLUMN review_status SET NOT NULL;;

-- Drop meta_review column from user_notification_subscriptions
ALTER TABLE IF EXISTS user_notification_subscriptions DROP COLUMN meta_review;;
