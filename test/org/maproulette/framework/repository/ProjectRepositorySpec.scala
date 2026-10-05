/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.repository

import org.joda.time.{DateTime, Months}
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget, Project, User}
import org.maproulette.framework.psql.Query
import org.maproulette.framework.psql.filter.BaseParameter
import org.maproulette.framework.util.{FrameworkHelper, ProjectRepoTag}
import org.maproulette.session.{SearchChallengeParameters, SearchLocation, SearchParameters}
import play.api.Application

/**
  * @author mcuthbert
  */
class ProjectRepositorySpec(implicit val application: Application) extends FrameworkHelper {
  val repository: ProjectRepository =
    this.application.injector.instanceOf(classOf[ProjectRepository])

  "ProjectRepository" should {
    "perform a basic query correctly" taggedAs ProjectRepoTag in {
      val project = this.repository
        .query(Query.simple(List(BaseParameter(Project.FIELD_ID, this.defaultProject.id))))
      project.head mustEqual this.defaultProject
    }

    "create a new project" taggedAs ProjectRepoTag in {
      val createdProject =
        this.repository.create(Project(-1, User.superUser.osmProfile.id, "CreateProjectTest"))
      val retrievedProject = this.repository.retrieve(createdProject.id)
      retrievedProject.get mustEqual createdProject
    }

    "retrieve a project" taggedAs ProjectRepoTag in {
      val retrievedProject = this.repository.retrieve(this.defaultProject.id)
      retrievedProject.get mustEqual this.defaultProject
    }

    "update a project" taggedAs ProjectRepoTag in {
      val newCreatedProject =
        this.repository.create(Project(-1, User.superUser.osmProfile.id, "UpdateProjectTest"))
      val retrievedProject = this.repository.retrieve(newCreatedProject.id)
      retrievedProject.get mustEqual newCreatedProject

      // create a new user for the ownership of the project
      val randomUser = this.serviceManager.user
        .create(this.getTestUser(456677, "DummyProjectUser"), User.superUser)
      val randomDateTime = DateTime.now.minus(Months.ONE)
      // update everything, including things actually not expected to be updated like created, modified, grants and deleted
      val updatedProject = Project(
        newCreatedProject.id,
        randomUser.osmProfile.id,
        "UPDATE_NAME",
        description = Some("UPDATE_DESCRIPTION"),
        displayName = Some("UPDATE_DISPLAYNAME"),
        enabled = false,
        isVirtual = Some(true),
        featured = false,
        deleted = true,
        created = randomDateTime,
        modified = randomDateTime,
        grants = List(
          Grant(
            -1,
            "RANDOM",
            Grantee.user(randomUser.id),
            Grant.ROLE_ADMIN,
            GrantTarget.project(newCreatedProject.id)
          )
        )
      )
      this.repository.update(updatedProject)
      val dbUpdatedProject = this.repository.retrieve(newCreatedProject.id)
      dbUpdatedProject.get.id mustEqual newCreatedProject.id
      dbUpdatedProject.get.owner mustEqual randomUser.osmProfile.id
      dbUpdatedProject.get.name mustEqual "UPDATE_NAME"
      dbUpdatedProject.get.description mustEqual Some("UPDATE_DESCRIPTION")
      dbUpdatedProject.get.displayName mustEqual Some("UPDATE_DISPLAYNAME")
      dbUpdatedProject.get.enabled mustEqual false
      dbUpdatedProject.get.isVirtual mustEqual Some(true)
      dbUpdatedProject.get.featured mustEqual false
      dbUpdatedProject.get.deleted mustEqual false
      dbUpdatedProject.get.created mustEqual newCreatedProject.created
      dbUpdatedProject.get.modified.isAfter(randomDateTime)
      dbUpdatedProject.get.grants.size mustEqual 0
    }

    "delete a project" taggedAs ProjectRepoTag in {
      val newCreatedProject =
        this.repository.create(Project(-1, User.superUser.osmProfile.id, "DeleteProjectTest"))
      val retrievedProject = this.repository.retrieve(newCreatedProject.id)
      retrievedProject.get mustEqual newCreatedProject
      this.repository.delete(newCreatedProject.id, false)
      val deletedProject = this.repository.retrieve(newCreatedProject.id)
      // it should still exist in the database because only the flag has been set
      deletedProject.get.deleted mustEqual true
      this.repository.delete(newCreatedProject.id, true)
      val reallyDeletedProject = this.repository.retrieve(newCreatedProject.id)
      reallyDeletedProject mustEqual None
    }

    "get searched clustered points" taggedAs ProjectRepoTag in {
      // this is really just testing the validation of the process, as there is only 1 task as part of the test data
      val clusteredPoints = this.repository.getSearchedClusteredPoints(
        SearchParameters(
          challengeParams = SearchChallengeParameters(
            challengeTags = Some(List("test")),
            challengeSearch = Some("test"),
            challengeEnabled = Some(true)
          ),
          projectSearch = Some("test"),
          projectEnabled = Some(true),
          projectIds = Some(List(this.defaultProject.id)),
          location = Some(SearchLocation(1.5, 2.5, 3.5, 4.5))
        )
      )
      clusteredPoints.isEmpty mustEqual true
    }

    "get clustered points" taggedAs ProjectRepoTag in {
      // this is really just testing the validation of the process, as there is only 1 task as part of the test data
      val clusteredPoints = this.repository
        .getClusteredPoints(Some(this.defaultProject.id), List(this.defaultChallenge.id), true)
      clusteredPoints.isEmpty mustEqual true
    }
  }

  override implicit val projectTestName: String = "ProjectRepositorySpecProject"
}
