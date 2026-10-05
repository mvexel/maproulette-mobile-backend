# --- !Ups

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
    new_task_task_status integer;;
  BEGIN
    return_id := task_id;;
    IF (SELECT task_id) = -1 THEN
      IF (task_status IS NULL) THEN
        -- default to created
        new_task_task_status := 0;;
      ELSE
        -- set task status to passed in status
        new_task_task_status := task_status;;
      END IF;;

      BEGIN
        SELECT ST_COLLECT(ST_SETSRID(ST_MAKEVALID(ST_GEOMFROMGEOJSON(jsonb_array_elements_Text::JSONB->>'geometry')), 4326)) INTO geojson_geom
		    FROM JSONB_ARRAY_ELEMENTS_TEXT(geo_json->'features');;

        INSERT INTO tasks (name, parent_id, status, instruction, priority, geojson, location, geom, cooperative_work_json)
        VALUES (task_name, task_parent_id, new_task_task_status, task_instruction, task_priority, geo_json, ST_Centroid(geojson_geom), geojson_geom, cooperative_work) RETURNING id INTO return_id;;
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

# --- !Downs
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
