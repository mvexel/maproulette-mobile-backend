/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.framework.model.{User, Task}
import org.maproulette.framework.util.{FrameworkHelper, UserMetricsTag}
import play.api.Application

/**
  * @author mcuthbert
  */
class UserMetricsServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val service: UserMetricService = this.serviceManager.userMetrics
  val userService: UserService   = this.serviceManager.user

  "UserMetricService" should {
    "get metrics for a user" taggedAs UserMetricsTag in {
      //TODO expand these tests.
      val insertedUser =
        this.userService.create(this.getTestUser(19, "UpdateUserService"), User.superUser)
      val userMetrics =
        this.service.getMetricsForUser(
          insertedUser.id,
          insertedUser,
          -1,
          -1,
          -1,
          "",
          "",
          "",
          "",
          "",
          ""
        )
    }

    "updates the users score" taggedAs UserMetricsTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(19, "UpdateUserService"), User.superUser)
      val updatedUser = this.service.updateUserScore(
        Some(Task.STATUS_FIXED),
        Some(1000),
        Some(1),
        true,
        true,
        Some(0),
        insertedUser.id
      )
      updatedUser.get.score.get mustEqual (insertedUser.score.getOrElse(0) + 5)
    }

    "rolls back the users score" taggedAs UserMetricsTag in {
      val insertedUser =
        this.userService.create(this.getTestUser(19, "UpdateUserService"), User.superUser)
      val currentScore = insertedUser.score.getOrElse(0)

      var updatedUser = this.service.updateUserScore(
        Some(Task.STATUS_ALREADY_FIXED),
        Some(1000),
        Some(1),
        true,
        true,
        Some(0),
        insertedUser.id
      )
      updatedUser = this.service.rollbackUserScore(Task.STATUS_ALREADY_FIXED, insertedUser.id)
      updatedUser.get.score.get mustEqual currentScore
    }
  }
  override implicit val projectTestName: String = "UserMetricsSpecProject"
}
