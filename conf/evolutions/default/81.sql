# --- !Ups
ALTER TABLE challenges ADD COLUMN changeset_url BOOLEAN DEFAULT false;;

# --- !Downs
ALTER TABLE IF EXISTS challenges DROP COLUMN changeset_url;;