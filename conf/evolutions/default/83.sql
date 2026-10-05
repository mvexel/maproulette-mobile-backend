# --- !Ups
CREATE TABLE IF NOT EXISTS CHALLENGE_COMMENTS
(
  id SERIAL NOT NULL PRIMARY KEY,
  osm_id integer NOT NULL,
  created timestamp without time zone DEFAULT NOW(),
  comment character varying,
  challenge_id integer NOT NULL,
  project_id integer NOT NULL
);;

ALTER TABLE CHALLENGE_COMMENTS 
  ADD CONSTRAINT challenge_comments_challenge_id_fkey FOREIGN KEY (challenge_id)
  REFERENCES challenges (id) MATCH SIMPLE
  ON UPDATE CASCADE ON DELETE CASCADE,
  ADD CONSTRAINT challenge_comments_project_id_fkey FOREIGN KEY (project_id)
  REFERENCES projects (id) MATCH SIMPLE
  ON UPDATE CASCADE ON DELETE CASCADE;;

# --- !Downs
DROP TABLE IF EXISTS CHALLENGE_COMMENTS;;