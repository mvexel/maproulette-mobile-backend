# --- !Ups
ALTER TABLE task_review_history ADD COLUMN error_tags VARCHAR;;
ALTER TABLE task_review ADD COLUMN error_tags VARCHAR;;
ALTER TABLE user_notifications ADD COLUMN error_tags VARCHAR;;

DELETE FROM tags WHERE name = 'Geometry' AND tag_type = 'error';
INSERT INTO tags (name, tag_type) VALUES ('Geometry', 'error');
DELETE FROM tags WHERE name = 'Tagging' AND tag_type = 'error';
INSERT INTO tags (name, tag_type) VALUES ('Tagging', 'error');
DELETE FROM tags WHERE name = 'Incomplete' AND tag_type = 'error';
INSERT INTO tags (name, tag_type) VALUES ('Incomplete', 'error');

# --- !Downs
ALTER TABLE IF EXISTS task_review_history DROP COLUMN error_tags;;
ALTER TABLE IF EXISTS task_review DROP COLUMN error_tags;;
ALTER TABLE IF EXISTS user_notifications DROP COLUMN error_tags;;

DELETE FROM tags WHERE name = 'Geometry' AND tag_type = 'error';
DELETE FROM tags WHERE name = 'Tagging' AND tag_type = 'error';
DELETE FROM tags WHERE name = 'Incomplete' AND tag_type = 'error';