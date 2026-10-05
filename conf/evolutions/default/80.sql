# --- !Ups
ALTER TABLE challenges ADD COLUMN task_bundle_id_property VARCHAR;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN task_bundle_id_property;;