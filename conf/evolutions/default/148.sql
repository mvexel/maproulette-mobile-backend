# --- MapRoulette Scheme

# --- !Ups

-- Answers on the map hold their task for good (fork only, #50): answers that went on_map before
-- that held only until their old hold_until. Places found gone before #49 and #50 get what the
-- hourly check now does for a gone place: waiting answers skipped (element_gone) and the task
-- recorded in choice_stale, so discovery stops offering it. Idempotent.
UPDATE choice_pending SET hold_until = '9999-12-31T00:00:00Z'
  WHERE state = 'on_map' AND hold_until < '9999-12-31T00:00:00Z';;
INSERT INTO choice_stale(task_id, reason, detail, element_version, payload_md5)
  SELECT t.id, 'element_gone', '[]'::jsonb, NULL, md5(t.cooperative_work_json::text)
  FROM choice_review_places rp JOIN tasks t ON t.id = rp.task_id
  WHERE rp.state = 'gone' AND t.cooperative_work_json IS NOT NULL
  ON CONFLICT (task_id) DO NOTHING;;
UPDATE choice_pending p SET state = 'skipped_stale', result = '{"reason": "element_gone"}'::jsonb,
    updated_at = NOW()
  FROM choice_review_places rp
  WHERE rp.task_id = p.task_id AND rp.state = 'gone' AND p.state = 'pending';;

# --- !Downs
-- Nothing to undo: the earlier values are not kept, and the rows match what the code now writes.
SELECT 1;;
