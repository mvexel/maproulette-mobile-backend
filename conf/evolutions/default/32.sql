# --- MapRoulette Scheme

# --- !Ups
-- Add dispute columns to user_metrics

ALTER TABLE "user_metrics" ADD COLUMN total_disputed_as_mapper integer DEFAULT 0;;
ALTER TABLE "user_metrics" ADD COLUMN total_disputed_as_reviewer integer DEFAULT 0;;

ALTER TABLE "user_metrics_history" ADD COLUMN total_disputed_as_mapper integer DEFAULT 0;;
ALTER TABLE "user_metrics_history" ADD COLUMN total_disputed_as_reviewer integer DEFAULT 0;;


# --- !Downs
ALTER TABLE "user_metrics" DROP COLUMN total_disputed_as_mapper;;
ALTER TABLE "user_metrics" DROP COLUMN total_disputed_as_reviewer;;

ALTER TABLE "user_metrics_history" DROP COLUMN total_disputed_as_mapper;;
ALTER TABLE "user_metrics_history" DROP COLUMN total_disputed_as_reviewer;;
