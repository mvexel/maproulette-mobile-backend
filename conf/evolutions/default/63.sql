# --- MapRoulette Scheme

# --- !Ups
CREATE TABLE IF NOT EXISTS groups
(
  id SERIAL NOT NULL PRIMARY KEY,
  name character varying NOT NULL,
  description character varying,
  avatar_url character varying,
  group_type integer DEFAULT 0,
  created timestamp without time zone DEFAULT NOW(),
  modified timestamp without time zone DEFAULT NOW()
);;

SELECT create_index_if_not_exists('groups', 'name', '(lower(name))', true);;
SELECT create_index_if_not_exists('groups', 'group_type', '(group_type)');;

CREATE TABLE IF NOT EXISTS group_members
(
  id SERIAL NOT NULL PRIMARY KEY,
  group_id integer NOT NULL,
  member_type integer NOT NULL,
  member_id integer NOT NULL,
  status integer DEFAULT 0,
  created timestamp without time zone DEFAULT NOW(),
  modified timestamp without time zone DEFAULT NOW(),
  CONSTRAINT group_members_group_id FOREIGN KEY (group_id)
    REFERENCES groups(id) MATCH SIMPLE
    ON UPDATE CASCADE ON DELETE CASCADE
);;

SELECT create_index_if_not_exists('group_members', 'group_id', '(group_id)');;
SELECT create_index_if_not_exists('group_members', 'member_type_member_id', '(member_type, member_id)');;
SELECT create_index_if_not_exists('group_members', 'group_member_type_member_id', '(group_id, member_type, member_id)', true);;

# --- !Downs
DROP TABLE IF EXISTS group_members;;
DROP TABLE IF EXISTS groups;;
