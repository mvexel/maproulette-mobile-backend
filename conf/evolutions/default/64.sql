# --- !Ups
-- Add references to Following and Followers groups to users, as well as
-- user setting for allowing others to follow them (defaults to yes)
ALTER TABLE IF EXISTS users ADD COLUMN following_group INTEGER, ADD COLUMN followers_group INTEGER, ADD COLUMN allow_following BOOLEAN DEFAULT TRUE;;

# --- !Downs
-- remove Following and Followers type groups
DELETE FROM groups WHERE group_type IN (2, 3);;
ALTER TABLE IF EXISTS users DROP COLUMN following_group, DROP COLUMN followers_group, DROP COLUMN allow_following;;
