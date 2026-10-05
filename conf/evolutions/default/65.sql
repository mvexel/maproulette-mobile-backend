# --- !Ups
-- Add subscription options for team and follow notifications
ALTER TABLE IF EXISTS user_notification_subscriptions ADD COLUMN team integer NOT NULL DEFAULT 1, ADD COLUMN follow integer NOT NULL DEFAULT 1;;

# --- !Downs
ALTER TABLE IF EXISTS user_notification_subscriptions DROP COLUMN team, DROP COLUMN follow;;
