-- Guests' answers for one campaign, for scripts/woodbine_report.py --pending. Read only.
-- psql "$DATABASE_URL" -v challenge=<challenge id> -f scripts/woodbine-pending.sql > pending.csv
-- No guest ids or emails: only the element, the state and what was answered.
\copy (SELECT t.cooperative_work_json->>'element' AS element, p.state, p.answered_at, (p.body->'answers')::text AS answers, p.body->>'outcome' AS outcome FROM choice_pending p JOIN tasks t ON t.id = p.task_id WHERE p.challenge_id = :challenge ORDER BY p.id) TO STDOUT WITH CSV HEADER
