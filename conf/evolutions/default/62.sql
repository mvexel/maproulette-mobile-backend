# --- MapRoulette Scheme

# --- !Ups
-- Clean up empty task tags.
-- (This will also clean up the join tables as well. tags_on_tasks/tags_on_challenges)
DELETE FROM tags where name='';

-- Combine groups and user_groups into new grants table
CREATE TABLE IF NOT EXISTS grants
(
  id SERIAL NOT NULL PRIMARY KEY,
  name character varying NOT NULL,
  grantee_id integer NOT NULL,
  grantee_type integer NOT NULL,
  role integer NOT NULL,
  object_id integer NOT NULL,
  object_type integer NOT NULL,
  UNIQUE(grantee_id, grantee_type, role, object_id, object_type)
);;

SELECT create_index_if_not_exists('grants', 'object', '(object_id, object_type)');;
SELECT create_index_if_not_exists('grants', 'grantee', '(grantee_id, grantee_type)');;

-- Populate grants table. All existing grantees are users (5) and all existing
-- objects are projects (0)
INSERT INTO grants(grantee_id, grantee_type, role, object_id, object_type, name)
SELECT u.id, 5 as grantee_type, g.group_type, g.project_id, 0 as object_type, 'User ' || u.id || ' has ' || (CASE g.group_type WHEN -1 THEN 'Superuser' WHEN 1 THEN 'Admin' WHEN 2 THEN 'Write' WHEN 3 THEN 'Read' ELSE 'Other' END) || ' on Project ' || g.project_id as name
FROM users u, user_groups ug, groups g
WHERE ug.osm_user_id = u.osm_id AND ug.group_id = g.id;;

-- Grant admin role to any project owners who don't already have it on their own projects
INSERT INTO grants(grantee_id, grantee_type, role, object_id, object_type, name)
SELECT u.id, 5, 1, p.id, 0, 'User ' || u.id || 'has Admin on Project ' || p.id
FROM users u
INNER JOIN projects p on p.owner_id = u.osm_id
WHERE u.id NOT IN (
  SELECT grantee_id FROM grants
  WHERE grantee_type = 5 AND role = 1 AND object_type = 0 AND object_id = p.id
);;

-- Update triggers
CREATE OR REPLACE FUNCTION on_user_delete() RETURNS TRIGGER AS $$
BEGIN
  DELETE FROM grants WHERE grantee_type = 5 AND grantee_id = old.id;;
  DELETE FROM locked WHERE user_id = old.id;;
  RETURN old;;
END
$$
LANGUAGE plpgsql VOLATILE;;

DROP TABLE user_groups;;
DROP TABLE groups;;

# --- !Downs
CREATE TABLE groups
(
  id SERIAL NOT NULL PRIMARY KEY,
  project_id integer NOT NULL,
  name character varying NOT NULL,
  group_type integer NOT NULL,
  CONSTRAINT groups_project_id_fkey FOREIGN KEY (project_id)
    REFERENCES projects(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED
);;

CREATE TABLE user_groups
(
  id SERIAL NOT NULL PRIMARY KEY,
  osm_user_id integer NOT NULL,
  group_id integer NOT NULL,
  CONSTRAINT ug_user_id_fkey FOREIGN KEY (osm_user_id)
    REFERENCES users(osm_id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
    DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT ug_group_id_fkey FOREIGN KEY (group_id)
    REFERENCES groups(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
);;

-- Update triggers
CREATE OR REPLACE FUNCTION on_user_delete() RETURNS TRIGGER AS $$
BEGIN
  DELETE FROM user_groups WHERE osm_user_id = old.osm_id;;
  DELETE FROM locked WHERE user_id = old.id;;
  RETURN old;;
END
$$
LANGUAGE plpgsql VOLATILE;;

-- Populate groups. Each project gets an admin, write, and read group. Group
-- names are the normalized project name (replacing whitespace with underscores
-- and consolidating any resulting dup underscores) followed by the name of
-- the group type

-- Populate special superuser group
INSERT INTO groups(id, project_id, name, group_type)
VALUES (-999, 0, 'SUPERUSERS', -1);;

-- Populate Admin groups for each project
INSERT INTO groups(project_id, name, group_type)
SELECT p.id, regexp_replace(regexp_replace(p.name || '_Admin', '\s+', '_'), '_+', '_') as name, 1 as group_type
FROM projects p;;

-- Populate Write groups for each project
INSERT INTO groups(project_id, name, group_type)
SELECT p.id, regexp_replace(regexp_replace(p.name || '_Write', '\s+', '_'), '_+', '_') as name, 2 as group_type
FROM projects p;;

-- Populate Read groups for each project
INSERT INTO groups(project_id, name, group_type)
SELECT p.id, regexp_replace(regexp_replace(p.name || '_Read', '\s+', '_'), '_+', '_') as name, 3 as group_type
FROM projects p;;

-- Populate user_groups rows from grants for users (5) on projects (0), which
-- is all that was supported by the prior groups/user_groups scheme
INSERT INTO user_groups(osm_user_id, group_id)
SELECT u.osm_id, g.id
FROM users u, groups g, grants
WHERE grants.grantee_type = 5 AND grants.grantee_id = u.id AND
      grants.object_type = 0 AND grants.object_id = g.project_id AND
      grants.role = g.group_type;;


DROP TABLE grants;;
