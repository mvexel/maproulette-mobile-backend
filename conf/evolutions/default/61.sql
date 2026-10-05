# --- !Ups
-- Rename suggested_fixes to cooperative_work
ALTER TABLE tasks RENAME COLUMN suggestedfix_geojson to cooperative_work_json;;

-- Rename has_suggested_fixes and change from boolean to int to track cooperative type
ALTER TABLE challenges RENAME COLUMN has_suggested_fixes TO cooperative_type;;
ALTER TABLE challenges ALTER cooperative_type DROP DEFAULT;;
ALTER TABLE challenges ALTER cooperative_type TYPE INTEGER USING CASE WHEN cooperative_type=TRUE THEN 1 ELSE 0 END;;
ALTER TABLE challenges ALTER cooperative_type SET DEFAULT 0;;

SELECT add_drop_column('locked', 'changeset_id', 'integer not null default(-1)');;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change makes sure not to reset the task status if a task is disabled or declared a false positive
DROP FUNCTION IF EXISTS update_task(text,bigint,text,integer,jsonb,jsonb,bigint,integer,bigint,text,timestamp without time zone,integer,integer,integer,timestamp without time zone);;
CREATE OR REPLACE FUNCTION update_task(task_name text,
                                        task_parent_id bigint,
                                        task_instruction text,
                                        task_status integer,
                                        geo_json jsonb,
                                        cooperative_work jsonb DEFAULT NULL,
                                        task_id bigint DEFAULT -1,
                                        task_priority integer DEFAULT 0,
                                        task_changeset_id bigint DEFAULT -1,
                                        reset_interval text DEFAULT '7 days',
                                        task_mapped_on timestamp DEFAULT NULL,
                                        task_review_status integer DEFAULT NULL,
                                        task_review_requested_by integer DEFAULT NULL,
                                        task_reviewed_by integer DEFAULT NULL,
                                        task_reviewed_at timestamp DEFAULT NULL
                                      ) RETURNS integer as $$
  DECLARE
    update_id integer;;
    update_modified timestamp without time zone;;
    update_status integer;;
    new_status integer;;
    geojson_geom geometry;;
  BEGIN
    IF (SELECT task_id) = -1 THEN
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;;
    ELSE
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;;
    END IF;;
    -- Only if task status is not null set/update it to the new status
    IF task_status IS NOT NULL THEN
      new_status := task_status;;
    ELSE
      new_status := update_status;;
    END IF;;
    -- only reset the status if the task is not currently disabled or set as a false positive and all other criteria is met.
    IF update_status = task_status AND NOT (update_status = 9 OR update_status = 2) AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
      new_status := 0;;
    END IF;;
    SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
		FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');;
    UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority,
                     changeset_id = task_changeset_id, mapped_on = task_mapped_on, geojson = geo_json,
                     cooperative_work_json = cooperative_work,
                     location = ST_Centroid(geojson_geom),
                     geom = geojson_geom
                     WHERE id = update_id;;

    -- Note in status actions if status has changed
    -- Since only when someone with admin privileges to a challenge can call this update
    -- to change the status we just set osm_user_id to -1 (thereby not impacting any user scores).
    -- To determine who did the update check the actions table.
    IF update_status != new_status THEN
      INSERT INTO status_actions (osm_user_id, project_id, challenge_id, task_id, old_status, status)
        VALUES (-1, (SELECT parent_id FROM challenges WHERE id = task_parent_id),
                task_parent_id, update_id, update_status, new_status);;
    END IF;;

    --
    -- Only update task_review table if we actually have a task_review_status otherwise we will
    -- end up with weird empty rows. And if the new status is created we need to delete any requested review
    --
    IF task_review_status IS NOT NULL THEN
      UPDATE task_review SET review_status = task_review_status, review_requested_by = task_review_requested_by,
                             reviewed_by = task_reviewed_by, reviewed_at = task_reviewed_at WHERE task_review.task_id = update_id;;
    ELSE IF new_status = 0 THEN
        DELETE FROM task_review WHERE task_review.task_id = update_id;;
      END IF;;
    END IF;;
    RETURN update_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change adds the mapped_on, review_status, review_requested_by, reviewed_by
DROP FUNCTION IF EXISTS create_update_task(text,bigint,text,integer,jsonb,jsonb,bigint,integer,bigint,text,timestamp,integer,integer,integer,timestamp);;
CREATE FUNCTION create_update_task(task_name text,
                                              task_parent_id bigint,
                                              task_instruction text,
                                              task_status integer,
                                              geo_json jsonb,
                                              cooperative_work jsonb DEFAULT NULL,
                                              task_id bigint DEFAULT -1,
                                              task_priority integer DEFAULT 0,
                                              task_changeset_id bigint DEFAULT -1,
                                              reset_interval text DEFAULT '7 days',
                                              task_mapped_on timestamp DEFAULT NULL,
                                              task_review_status integer DEFAULT NULL,
                                              task_review_requested_by integer DEFAULT NULL,
                                              task_reviewed_by integer DEFAULT NULL,
                                              task_reviewed_at timestamp DEFAULT NULL) RETURNS integer as $$
  DECLARE
    return_id integer;;
    geojson_geom geometry;;
  BEGIN
    return_id := task_id;;
    IF (SELECT task_id) = -1 THEN
      BEGIN
        SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
		FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');;

        INSERT INTO tasks (name, parent_id, instruction, priority, geojson, location, geom, cooperative_work_json)
        VALUES (task_name, task_parent_id, task_instruction, task_priority, geo_json, ST_Centroid(geojson_geom), geojson_geom, cooperative_work) RETURNING id INTO return_id;;
        EXCEPTION WHEN UNIQUE_VIOLATION THEN
        SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, cooperative_work, task_id, task_priority,
                                            task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
      END;;
    ELSE
      PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, cooperative_work, task_id, task_priority,
                            task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
    END IF;;
    RETURN return_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

DROP FUNCTION IF EXISTS update_geometry(bigint);;
CREATE OR REPLACE FUNCTION update_geometry(task_identifier bigint)
	RETURNS TABLE(geo TEXT, loc TEXT, fix_geo TEXT) AS $$
BEGIN
    UPDATE tasks t SET geojson = geoms.geometries FROM (SELECT ROW_TO_JSON(fc)::JSONB AS geometries
                      FROM ( SELECT 'FeatureCollection' AS type, ARRAY_TO_JSON(array_agg(f)) AS features
                               FROM ( SELECT 'Feature' AS type,
                                              ST_AsGeoJSON(lg.geom)::JSONB AS geometry,
                                              HSTORE_TO_JSON(lg.properties) AS properties
                                      FROM task_geometries AS lg
                                      WHERE task_id = task_identifier
                                ) AS f
                        )  AS fc) AS geoms WHERE id = task_identifier;;
    -- Update the geometry and location columns
    UPDATE tasks t SET geom = geoms.geometry, location = ST_CENTROID(geoms.geometry)
    FROM (SELECT ST_COLLECT(ST_MAKEVALID(geom)) AS geometry FROM (
            SELECT geom FROM task_geometries WHERE task_id = task_identifier
         ) AS innerQuery) AS geoms WHERE id = task_identifier;;
	 RETURN QUERY SELECT geojson::TEXT, ST_AsGeoJSON(location) AS geo_location, cooperative_work_json::TEXT FROM tasks
	 WHERE id = task_identifier;;
END
$$ LANGUAGE plpgsql VOLATILE;;

# --- !Downs
ALTER TABLE tasks RENAME COLUMN cooperative_work_json to suggestedfix_geojson;;
ALTER TABLE challenges RENAME COLUMN cooperative_type to has_suggested_fixes;;
ALTER TABLE challenges ALTER has_suggested_fixes DROP DEFAULT;;
ALTER TABLE challenges ALTER has_suggested_fixes TYPE BOOLEAN USING CASE WHEN has_suggested_fixes > 0 then TRUE ELSE FALSE END;;
ALTER TABLE challenges ALTER has_suggested_fixes SET DEFAULT FALSE;;

SELECT add_drop_column('locked', 'changeset_id', '', false);;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change makes sure not to reset the task status if a task is disabled or declared a false positive
DROP FUNCTION IF EXISTS update_task(text,bigint,text,integer,jsonb,jsonb,bigint,integer,bigint,text,timestamp without time zone,integer,integer,integer,timestamp without time zone);;
CREATE OR REPLACE FUNCTION update_task(task_name text,
                                         task_parent_id bigint,
                                         task_instruction text,
                                         task_status integer,
                                         geo_json jsonb,
                                         suggestedfix jsonb DEFAULT NULL,
                                         task_id bigint DEFAULT -1,
                                         task_priority integer DEFAULT 0,
                                         task_changeset_id bigint DEFAULT -1,
                                         reset_interval text DEFAULT '7 days',
                                         task_mapped_on timestamp DEFAULT NULL,
                                         task_review_status integer DEFAULT NULL,
                                         task_review_requested_by integer DEFAULT NULL,
                                         task_reviewed_by integer DEFAULT NULL,
                                         task_reviewed_at timestamp DEFAULT NULL
                                       ) RETURNS integer as $$
  DECLARE
    update_id integer;;
    update_modified timestamp without time zone;;
    update_status integer;;
    new_status integer;;
    geojson_geom geometry;;
  BEGIN
    IF (SELECT task_id) = -1 THEN
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;;
    ELSE
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;;
    END IF;;
    -- Only if task status is not null set/update it to the new status
    IF task_status IS NOT NULL THEN
      new_status := task_status;;
    ELSE
      new_status := update_status;;
    END IF;;
    -- only reset the status if the task is not currently disabled or set as a false positive and all other criteria is met.
    IF update_status = task_status AND NOT (update_status = 9 OR update_status = 2) AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
      new_status := 0;;
    END IF;;
    SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
		FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');;
    UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority,
                     changeset_id = task_changeset_id, mapped_on = task_mapped_on, geojson = geo_json,
                     suggestedfix_geojson = suggestedfix,
                     location = ST_Centroid(geojson_geom),
                     geom = geojson_geom
                     WHERE id = update_id;;

    -- Note in status actions if status has changed
    -- Since only when someone with admin privileges to a challenge can call this update
    -- to change the status we just set osm_user_id to -1 (thereby not impacting any user scores).
    -- To determine who did the update check the actions table.
    IF update_status != new_status THEN
      INSERT INTO status_actions (osm_user_id, project_id, challenge_id, task_id, old_status, status)
        VALUES (-1, (SELECT parent_id FROM challenges WHERE id = task_parent_id),
                task_parent_id, update_id, update_status, new_status);;
    END IF;;

    --
    -- Only update task_review table if we actually have a task_review_status otherwise we will
    -- end up with weird empty rows. And if the new status is created we need to delete any requested review
    --
    IF task_review_status IS NOT NULL THEN
      UPDATE task_review SET review_status = task_review_status, review_requested_by = task_review_requested_by,
                             reviewed_by = task_reviewed_by, reviewed_at = task_reviewed_at WHERE task_review.task_id = update_id;;
    ELSE IF new_status = 0 THEN
        DELETE FROM task_review WHERE task_review.task_id = update_id;;
      END IF;;
    END IF;;
    RETURN update_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change adds the mapped_on, review_status, review_requested_by, reviewed_by
DROP FUNCTION IF EXISTS create_update_task(text,bigint,text,integer,jsonb,jsonb,bigint,integer,bigint,text,timestamp without time zone,integer,integer,integer,timestamp without time zone);;
CREATE FUNCTION create_update_task(task_name text,
                                              task_parent_id bigint,
                                              task_instruction text,
                                              task_status integer,
                                              geo_json jsonb,
                                              suggestedfix jsonb DEFAULT NULL,
                                              task_id bigint DEFAULT -1,
                                              task_priority integer DEFAULT 0,
                                              task_changeset_id bigint DEFAULT -1,
                                              reset_interval text DEFAULT '7 days',
                                              task_mapped_on timestamp DEFAULT NULL,
                                              task_review_status integer DEFAULT NULL,
                                              task_review_requested_by integer DEFAULT NULL,
                                              task_reviewed_by integer DEFAULT NULL,
                                              task_reviewed_at timestamp DEFAULT NULL) RETURNS integer as $$
  DECLARE
    return_id integer;;
    geojson_geom geometry;;
  BEGIN
    return_id := task_id;;
    IF (SELECT task_id) = -1 THEN
      BEGIN
        SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
		FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');;

        INSERT INTO tasks (name, parent_id, instruction, priority, geojson, location, geom, suggestedfix_geojson)
        VALUES (task_name, task_parent_id, task_instruction, task_priority, geo_json, ST_Centroid(geojson_geom), geojson_geom, suggestedfix) RETURNING id INTO return_id;;
        EXCEPTION WHEN UNIQUE_VIOLATION THEN
        SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, suggestedfix, task_id, task_priority,
                                            task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
      END;;
    ELSE
      PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, geo_json, suggestedfix, task_id, task_priority,
                            task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
    END IF;;
    RETURN return_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

DROP FUNCTION IF EXISTS update_geometry(bigint);;
CREATE OR REPLACE FUNCTION update_geometry(task_identifier bigint)
	RETURNS TABLE(geo TEXT, loc TEXT, fix_geo TEXT) AS $$
BEGIN
    UPDATE tasks t SET geojson = geoms.geometries FROM (SELECT ROW_TO_JSON(fc)::JSONB AS geometries
                      FROM ( SELECT 'FeatureCollection' AS type, ARRAY_TO_JSON(array_agg(f)) AS features
                               FROM ( SELECT 'Feature' AS type,
                                              ST_AsGeoJSON(lg.geom)::JSONB AS geometry,
                                              HSTORE_TO_JSON(lg.properties) AS properties
                                      FROM task_geometries AS lg
                                      WHERE task_id = task_identifier
                                ) AS f
                        )  AS fc) AS geoms WHERE id = task_identifier;;
    -- Update the geometry and location columns
    UPDATE tasks t SET geom = geoms.geometry, location = ST_CENTROID(geoms.geometry)
    FROM (SELECT ST_COLLECT(ST_MAKEVALID(geom)) AS geometry FROM (
            SELECT geom FROM task_geometries WHERE task_id = task_identifier
         ) AS innerQuery) AS geoms WHERE id = task_identifier;;
	 RETURN QUERY SELECT geojson::TEXT, ST_AsGeoJSON(location) AS geo_location, suggestedfix_geojson::TEXT FROM tasks
	 WHERE id = task_identifier;;
END
$$ LANGUAGE plpgsql VOLATILE;;
