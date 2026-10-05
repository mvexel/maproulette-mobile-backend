# --- MapRoulette Scheme

# --- !Ups

-- Add Mapped on timestamp
ALTER TABLE "tasks" ADD COLUMN mapped_on timestamp without time zone DEFAULT NULL;;

-- DROP FUNCTION create_update_task(text,bigint,text,integer,bigint,integer,bigint,text);;
-- DROP FUNCTION update_task(text,bigint,text,integer,bigint,integer,bigint,text);;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change adds the mapped_on, review_status, review_requested_by, reviewed_by
CREATE OR REPLACE FUNCTION create_update_task(task_name text,
                                              task_parent_id bigint,
                                              task_instruction text,
                                              task_status integer,
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
  BEGIN
    return_id := task_id;;
    IF (SELECT task_id) = -1 THEN
      BEGIN
        INSERT INTO tasks (name, parent_id, instruction,  priority) VALUES (task_name, task_parent_id, task_instruction, task_priority) RETURNING id INTO return_id;;
        EXCEPTION WHEN UNIQUE_VIOLATION THEN
        SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
      END;;
    ELSE
      PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval, task_mapped_on, task_review_status, task_review_requested_by, task_reviewed_by, task_reviewed_at);;
    END IF;;
    RETURN return_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION update_task(task_name text,
                                         task_parent_id bigint,
                                         task_instruction text,
                                         task_status integer,
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
  BEGIN
    IF (SELECT task_id) = -1 THEN
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;;
    ELSE
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;;
    END IF;;
    new_status := task_status;;
    IF update_status = task_status AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
      new_status := 0;;
    END IF;;
    UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority,
                     changeset_id = task_changeset_id, mapped_on = task_mapped_on WHERE id = update_id;;
    UPDATE task_review SET review_status = task_review_status, review_requested_by = task_review_requested_by,
                           reviewed_by = task_reviewed_by, reviewed_at = task_reviewed_at WHERE task_review.task_id = update_id;;
    RETURN update_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

# --- !Downs
ALTER TABLE "tasks" DROP COLUMN mapped_on;;

DROP FUNCTION create_update_task(text,bigint,text,integer,bigint,integer,bigint,text,timestamp,integer,integer, integer, timestamp);;
DROP FUNCTION update_task(text,bigint,text,integer,bigint,integer,bigint,text,timestamp,integer,integer, integer, timestamp);;

-- Creates or updates and task. Will also check if task status needs to be updated
-- This change simply rolls back this function
CREATE OR REPLACE FUNCTION create_update_task(task_name text,
                                              task_parent_id bigint,
                                              task_instruction text,
                                              task_status integer,
                                              task_id bigint DEFAULT -1,
                                              task_priority integer DEFAULT 0,
                                              task_changeset_id bigint DEFAULT -1,
                                              reset_interval text DEFAULT '7 days') RETURNS integer as $$
  DECLARE
    return_id integer;;
  BEGIN
    return_id := task_id;;
    IF (SELECT task_id) = -1 THEN
      BEGIN
        INSERT INTO tasks (name, parent_id, instruction,  priority) VALUES (task_name, task_parent_id, task_instruction, task_priority) RETURNING id INTO return_id;;
        EXCEPTION WHEN UNIQUE_VIOLATION THEN
        SELECT INTO return_id update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval);;
      END;;
    ELSE
      PERFORM update_task(task_name, task_parent_id, task_instruction, task_status, task_id, task_priority, task_changeset_id, reset_interval);;
    END IF;;
    RETURN return_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;

CREATE OR REPLACE FUNCTION update_task(task_name text,
                                         task_parent_id bigint,
                                         task_instruction text,
                                         task_status integer,
                                         task_id bigint DEFAULT -1,
                                         task_priority integer DEFAULT 0,
                                         task_changeset_id bigint DEFAULT -1,
                                         reset_interval text DEFAULT '7 days') RETURNS integer as $$
  DECLARE
    update_id integer;;
    update_modified timestamp without time zone;;
    update_status integer;;
    new_status integer;;
  BEGIN
    IF (SELECT task_id) = -1 THEN
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE name = task_name AND parent_id = task_parent_id;;
    ELSE
      SELECT id, modified, status INTO update_id, update_modified, update_status FROM tasks WHERE id = task_id;;
    END IF;;
    new_status := task_status;;
    IF update_status = task_status AND (SELECT AGE(NOW(), update_modified)) > reset_interval::INTERVAL THEN
      new_status := 0;;
    END IF;;
    UPDATE tasks SET name = task_name, instruction = task_instruction, status = new_status, priority = task_priority, changeset_id = task_changeset_id WHERE id = update_id;;
    RETURN update_id;;
  END
  $$
  LANGUAGE plpgsql VOLATILE;;
