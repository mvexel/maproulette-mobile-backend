/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.maproulette.exception.{InvalidException}
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget, User, Project}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.service.GrantService
import org.maproulette.data._
import org.maproulette.framework.util.{FrameworkHelper, GrantTag}
import play.api.Application

/**
  * @author nrotstan
  */
class GrantRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: GrantRepository = this.application.injector.instanceOf(classOf[GrantRepository])
  val service: GrantService       = this.serviceManager.grant

  var defaultGrant: Grant = null
  var randomUser: User    = null

  "GrantRepository" should {
    "perform a basic query" taggedAs GrantTag in {
      val grants = this.repository
        .query(Query.simple(List(BaseParameter(Grant.FIELD_ID, this.defaultGrant.id))))
      grants.size mustEqual 1
      grants.head.id mustEqual this.defaultGrant.id
    }

    "retrieve a grant" taggedAs GrantTag in {
      val grant = this.repository.retrieve(this.defaultGrant.id)
      grant.get mustEqual this.defaultGrant

      val nonGrant = this.repository.retrieve(2345L)
      nonGrant.isEmpty mustEqual true
    }

    "create a grant" taggedAs GrantTag in {
      val createdGrant = this.repository
        .create(
          this.setupProjectGrant(this.randomUser, Grant.ROLE_WRITE_ACCESS, this.defaultProject)
        )
      val retrievedGrant = this.repository.retrieve(createdGrant.get.id)
      retrievedGrant.get mustEqual createdGrant.get
    }

    "not allow a grant to be updated" taggedAs GrantTag in {
      intercept[InvalidException] {
        this.repository.update(defaultGrant.copy(role = Grant.ROLE_READ_ONLY))
      }
    }

    "delete a grant" taggedAs GrantTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(246810, "AnotherOUser"),
        User.superUser
      )
      val grant = this.repository
        .create(this.setupProjectGrant(anotherUser, Grant.ROLE_WRITE_ACCESS, this.defaultProject))
      this.repository.delete(grant.get.id)
      this.repository.retrieve(grant.get.id) mustEqual None
    }

    "delete multiple grants" taggedAs GrantTag in {
      val anotherUser = this.serviceManager.user.create(
        this.getTestUser(246810, "AnotherOUser"),
        User.superUser
      )
      this.repository.create(
        this.setupProjectGrant(anotherUser, Grant.ROLE_WRITE_ACCESS, this.defaultProject)
      )

      this.repository.deleteGrants(
        Query.simple(
          List(
            BaseParameter(Grant.FIELD_OBJECT_TYPE, ProjectType().typeId),
            BaseParameter(Grant.FIELD_OBJECT_ID, this.defaultProject.id)
          )
        )
      )

      this.repository
        .query(
          Query.simple(
            List(
              BaseParameter(Grant.FIELD_OBJECT_TYPE, ProjectType().typeId),
              BaseParameter(Grant.FIELD_OBJECT_ID, this.defaultProject.id)
            )
          )
        )
        .size mustEqual 0
    }
  }

  override implicit val projectTestName: String = "GrantRepositorySpecProject"

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    defaultGrant = this.defaultProject.grants.head
    randomUser = this.serviceManager.user.create(
      this.getTestUser(12345, "RandomOUser"),
      User.superUser
    )
  }

  protected def setupProjectGrant(user: User, role: Int, project: Project): Grant = {
    Grant(-1, "", Grantee.user(user.id), role, GrantTarget.project(project.id))
  }
}
