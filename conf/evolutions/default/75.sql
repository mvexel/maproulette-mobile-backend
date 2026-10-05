# --- !Ups
-- Add array_distinct function for removing dups
DROP FUNCTION IF EXISTS array_distinct(arr anyarray);;
CREATE FUNCTION array_distinct(arr anyarray) RETURNS anyarray AS $$
  SELECT array_agg(elem order by ord)
    from (
      SELECT DISTINCT on(elem) elem, ord
      FROM unnest(arr) WITH ordinality AS arr(elem, ord)
      ORDER BY elem, ord
    ) s
$$
LANGUAGE SQL IMMUTABLE;;

-- Add achievements array column to user_metrics
ALTER TABLE IF EXISTS user_metrics
  ADD COLUMN achievements INTEGER[] NOT NULL DEFAULT '{}';;

-- Retroactively add some basic achievements to users who earned them:
-- FIXED_TASK
UPDATE user_metrics SET achievements = array_distinct(achievements || 16)
WHERE total_fixed > 0;;

-- FIXED_COOP_TASK
UPDATE user_metrics set achievements=array_distinct(achievements || 20)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select id from challenges where cooperative_type > 0
  )
);;

-- MAPPED_ROADS
UPDATE user_metrics set achievements=array_distinct(achievements || 1)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name = 'highway'
  )
);;

-- MAPPED_WATER
UPDATE user_metrics set achievements=array_distinct(achievements || 2)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name IN ('natural', 'water')
  )
);;

-- MAPPED_TRANSIT
UPDATE user_metrics set achievements=array_distinct(achievements || 3)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name IN ('railway', 'public_transport')
  )
);;

-- MAPPED_LANDUSE
UPDATE user_metrics set achievements=array_distinct(achievements || 4)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name IN ('landuse', 'boundary')
  )
);;

-- MAPPED_BUILDINGS
UPDATE user_metrics set achievements=array_distinct(achievements || 5)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name = 'building'
  )
);;

-- MAPPED_POI
UPDATE user_metrics set achievements=array_distinct(achievements || 6)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) from users u
  INNER JOIN status_actions sa ON sa.osm_user_id = u.osm_id
  WHERE sa.status = 1 AND sa.old_status != 1 AND challenge_id IN (
    select distinct(challenge_id) from tags_on_challenges
    INNER JOIN tags ON tags.id = tags_on_challenges.tag_id
    WHERE tags.name IN ('amenity', 'leisure')
  )
);;

-- REVIEWED_TASK
UPDATE user_metrics SET achievements = array_distinct(achievements || 17)
WHERE total_approved > 0 OR total_rejected > 0 OR total_assisted > 0;;

-- CREATED_CHALLENGE
UPDATE user_metrics SET achievements = array_distinct(achievements || 18)
WHERE user_metrics.user_id IN (
  SELECT DISTINCT(u.id) FROM USERS u
  INNER JOIN challenges c ON c.owner_id = u.osm_id
  INNER JOIN projects p ON c.parent_id = p.id
  WHERE (c.enabled = true and p.enabled = true) OR c.status = 5 -- enabled or finished
);;

-- POINTS_100 through POINTS_1000000
UPDATE user_metrics set achievements =
  CASE
    WHEN score >= 1000000 THEN array_distinct(achievements || '{7, 8, 9, 10, 11, 12, 13, 14, 15}'::int[])
    WHEN score >= 500000 THEN array_distinct(achievements || '{7, 8, 9, 10, 11, 12, 13, 14}'::int[])
    WHEN score >= 100000 THEN array_distinct(achievements || '{7, 8, 9, 10, 11, 12, 13}'::int[])
    WHEN score >= 50000 THEN array_distinct(achievements || '{7, 8, 9, 10, 11, 12}'::int[])
    WHEN score >= 10000 THEN array_distinct(achievements || '{7, 8, 9, 10, 11}'::int[])
    WHEN score >= 5000 THEN array_distinct(achievements || '{7, 8, 9, 10}'::int[])
    WHEN score >= 1000 THEN array_distinct(achievements || '{7, 8, 9}'::int[])
    WHEN score >= 500 THEN array_distinct(achievements || '{7, 8}'::int[])
    WHEN score >= 100 THEN array_distinct(achievements || 7)
  END
WHERE score >= 100;;


# --- !Downs
-- Remove achievements from user_metrics
ALTER TABLE IF EXISTS user_metrics
  DROP COLUMN achievements;;

-- Remove array_distinct function
DROP FUNCTION IF EXISTS array_distinct(arr anyarray);;
