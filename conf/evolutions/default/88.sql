# --- !Ups
CREATE INDEX IF NOT EXISTS idx_challenges_deleted ON challenges((1)) WHERE deleted; 
CREATE INDEX IF NOT EXISTS idx_challenges_owner_id_fkey ON challenges (owner_id); 
CREATE INDEX IF NOT EXISTS idx_status_actions_project_id_fkey ON status_actions (project_id); 
CREATE INDEX IF NOT EXISTS idx_task_review_task_id_fkey ON task_review (task_id); 
CREATE INDEX IF NOT EXISTS idx_task_comments_task_id_fkey ON task_comments (task_id); 
CREATE INDEX IF NOT EXISTS idx_task_bundles_task_id_fkey ON task_bundles (task_id); 
CREATE INDEX IF NOT EXISTS idx_virtual_challenge_tasks_task_id_fkey ON virtual_challenge_tasks (task_id); 
CREATE INDEX IF NOT EXISTS idx_saved_tasks_task_id_fkey ON saved_tasks (task_id); 
CREATE INDEX IF NOT EXISTS idx_task_comments_challenge_id_fkey ON task_comments (challenge_id); 
CREATE INDEX IF NOT EXISTS idx_status_actions_challenge_id_fkey ON status_actions (challenge_id); 
CREATE INDEX IF NOT EXISTS idx_tasks_challenge_parent_id_fkey ON tasks (parent_id); 

# --- !Downs
DROP INDEX IF EXISTS idx_challenges_deleted;
DROP INDEX IF EXISTS idx_challenges_owner_id_fkey;
DROP INDEX IF EXISTS idx_status_actions_project_id_fkey;
DROP INDEX IF EXISTS idx_task_review_task_id_fkey;
DROP INDEX IF EXISTS idx_task_comments_task_id_fkey;
DROP INDEX IF EXISTS idx_task_bundles_task_id_fkey;
DROP INDEX IF EXISTS idx_virtual_challenge_tasks_task_id_fkey;
DROP INDEX IF EXISTS idx_saved_tasks_task_id_fkey;
DROP INDEX IF EXISTS idx_task_comments_challenge_id_fkey;
DROP INDEX IF EXISTS idx_status_actions_challenge_id_fkey;
DROP INDEX IF EXISTS idx_tasks_challenge_parent_id_fkey;
