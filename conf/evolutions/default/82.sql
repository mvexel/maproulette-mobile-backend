# --- !Ups
ALTER TABLE user_notification_subscriptions ADD COLUMN review_count INTEGER DEFAULT 0;;
ALTER TABLE user_notification_subscriptions ADD COLUMN revision_count INTEGER DEFAULT 0;;

# --- !Downs
ALTER TABLE IF EXISTS user_notification_subscriptions DROP COLUMN review_count;;
ALTER TABLE IF EXISTS user_notification_subscriptions DROP COLUMN revision_count;;