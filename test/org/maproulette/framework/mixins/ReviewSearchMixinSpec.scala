/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.mixins

import anorm.NamedParameter
import org.maproulette.utils.TestSpec
import org.maproulette.framework.psql.Query
import org.maproulette.framework.mixins.ReviewSearchMixin
import org.maproulette.framework.model.{User, Task}
import org.maproulette.session._

/**
  * @author krotstan
  */
class ReviewSearchMixinSpec() extends TestSpec with ReviewSearchMixin {
  implicit var challengeID: Long = -1

  // Inserts named parameters into the string to make test comparison easier
  def insertParameters(s: String, namedParams: List[NamedParameter]): String = {
    var newString = s
    namedParams.foreach(p => {
      newString = newString.replaceAll(s"${p.name}", p.value.show)
    })
    newString.replaceAll("(?s)\\s+", " ").trim
  }

  "setupReviewSearchClause" should {
    "setup a review search" in {
      val params = new SearchParameters()

      val query =
        this.setupReviewSearchClause(Query.empty, User.superUser, permission, params)

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "(task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary))"
    }

    "enforce a permission check" in {
      val params = new SearchParameters()
      val query =
        this.setupReviewSearchClause(Query.empty, User.guestUser, permission, params)

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "(task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled) OR " +
          "(task_review.review_requested_by = -998) OR " +
          "(task_review.reviewed_by = -998)))"
    }

    "not allow mappers to review own work" in {
      val params = new SearchParameters()
      val query = this.setupReviewSearchClause(
        Query.empty,
        User.guestUser,
        permission,
        params,
        REVIEW_REQUESTED_TASKS
      )

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "((task_review.review_status = {0} OR task_review.review_status = {4})) " +
          "AND (task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled)) AND " +
          "task_review.review_requested_by <> -998)"
    }

    "exclude disputed" in {
      val params = new SearchParameters()
      val query = this.setupReviewSearchClause(
        Query.empty,
        User.guestUser,
        permission,
        params,
        REVIEW_REQUESTED_TASKS,
        false
      )

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "((task_review.review_status = {0})) " +
          "AND (task_review.review_status <> {5}) " +
          "AND ((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled)) AND task_review.review_requested_by <> -998)"
    }

    "only include saved challenges" in {
      val params = new SearchParameters()
      val query = this.setupReviewSearchClause(
        Query.empty,
        User.guestUser,
        permission,
        params,
        REVIEW_REQUESTED_TASKS,
        false,
        true
      )
      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "(c.id IN (SELECT challenge_id from saved_challenges sc WHERE sc.user_id = -998)) AND " +
          "((task_review.review_status = {0})) AND (task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled)) AND " +
          "task_review.review_requested_by <> -998)"
    }

    "only exclude other reviewers" in {
      val params = new SearchParameters()
      val query = this.setupReviewSearchClause(
        Query.empty,
        User.guestUser,
        permission,
        params,
        REVIEW_REQUESTED_TASKS,
        false,
        false,
        true
      )

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "((task_review.reviewed_by IS NULL OR task_review.reviewed_by = -998)) AND " +
          "((task_review.review_status = {0})) AND " +
          "(task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled)) AND task_review.review_requested_by <> -998)"
    }

    "filer by some search parameters" in {
      val params = new SearchParameters(
        taskParams = SearchTaskParameters(
          taskReviewStatus = Some(List(Task.REVIEW_STATUS_APPROVED)),
          taskStatus = Some(List(Task.STATUS_FIXED))
        ),
        challengeParams = SearchChallengeParameters(challengeIds = Some(List(123)))
      )
      val query =
        this.setupReviewSearchClause(Query.empty, User.guestUser, permission, params)

      insertParameters(query.sql(), query.parameters()) mustEqual
        "WHERE (task_review.review_requested_by IS NOT NULL) AND " +
          "(tasks.status IN (1)) AND (c.id IN (123)) AND " +
          "(task_review.review_status IN (1)) AND " +
          "(task_review.review_status <> {5}) AND " +
          "((tasks.bundle_id IS NULL OR tasks.is_bundle_primary)) AND " +
          "(((p.enabled AND c.enabled) OR " +
          "(task_review.review_requested_by = -998) OR " +
          "(task_review.reviewed_by = -998)))"
    }
  }
}
