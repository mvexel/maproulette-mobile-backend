# --- !Ups
ALTER TABLE challenges ADD COLUMN is_archived BOOLEAN DEFAULT false;;
ALTER TABLE projects ADD COLUMN is_archived BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN is_archived;;
ALTER TABLE IF EXISTS projects DROP COLUMN is_archived;;
