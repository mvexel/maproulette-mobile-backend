# --- !Ups
ALTER TABLE users ADD COLUMN see_tag_fix_suggestions BOOLEAN DEFAULT true;;

# --- !Downs
ALTER TABLE IF EXISTS users DROP COLUMN see_tag_fix_suggestions;;