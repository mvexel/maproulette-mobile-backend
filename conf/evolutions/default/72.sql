# --- !Ups
-- Due to a bug where mapper was being set as reviewer during a review revision,
-- we need to fix these task reviews by pulling the correct requested_by from
-- the most recent task_review_history entry.

WITH wrong_reviews AS (
  SELECT tr.id AS reviewId, tr.task_id AS taskId, th.requested_by AS requestedBy
  FROM task_review tr
  JOIN task_review_history th ON (tr.task_id = th.task_id)
  LEFT OUTER JOIN task_review_history th2 ON (tr.task_id = th2.task_id AND
      (th.reviewed_at < th2.reviewed_at OR (th.reviewed_at = th2.reviewed_at AND
       th.id < th2.id)))
  WHERE th2.task_id IS NULL AND tr.reviewed_by = tr.review_requested_by AND
        tr.review_requested_by NOT IN
          (SELECT th.requested_by FROM task_review_history th
           WHERE th.task_id = tr.task_id)
)
UPDATE task_review
SET review_requested_by = requestedBy, additional_reviewers = NULL
FROM wrong_reviews
WHERE id = wrong_reviews.reviewId AND task_id = wrong_reviews.taskId;
