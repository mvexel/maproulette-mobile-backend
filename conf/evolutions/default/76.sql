# --- !Ups
-- Add indexes for mapped_on
SELECT create_index_if_not_exists('tasks', 'tasks_mapped_on', '(mapped_on)');;
SELECT create_index_if_not_exists('tasks', 'tasks_mapped_on_status', '(mapped_on, status)');;


# --- !Downs
-- Remove indexes
DROP index "idx_tasks_tasks_mapped_on";
DROP index "idx_tasks_tasks_mapped_on_status";
