# --- !Ups
-- Add indexes for mapped_on
DROP index "idx_tasks_tasks_mapped_on";
DROP index "idx_tasks_tasks_mapped_on_status";

CREATE INDEX "idx_tasks_tasks_mapped_on_status_asc" on tasks (status, mapped_on asc);;
CREATE INDEX "idx_tasks_tasks_mapped_on_status_desc" on tasks (status, mapped_on desc);;

# --- !Downs
-- Remove indexes
DROP index "idx_tasks_tasks_mapped_on_status_asc";
DROP index "idx_tasks_tasks_mapped_on_status_desc";

SELECT create_index_if_not_exists('tasks', 'tasks_mapped_on', '(mapped_on)');;
SELECT create_index_if_not_exists('tasks', 'tasks_mapped_on_status', '(mapped_on, status)');;
