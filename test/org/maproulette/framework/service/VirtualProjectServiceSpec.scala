/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.service

import org.maproulette.exception.{InvalidException, NotFoundException}
import org.maproulette.framework.model.{Project, User}
import org.maproulette.framework.repository.VirtualProjectRepository
import org.maproulette.framework.util.{FrameworkHelper, VirtualProjectTag}
import play.api.Application

/**
  * @author mcuthbert
  */
class VirtualProjectServiceSpec(implicit val application: Application) extends FrameworkHelper {
  val repository: VirtualProjectRepository =
    this.application.injector.instanceOf(classOf[VirtualProjectRepository])
  val service: VirtualProjectService = this.serviceManager.virtualProject

  "VirtualProjectService" should {
    "add challenges to virtual project" taggedAs VirtualProjectTag in {
      val challenge =
        this.challengeDAL.insert(this.getTestChallenge("VP_ChallengeSerTest"), User.superUser)

      val project = this.getDefaultVirtualProject
      this.service.addChallenge(project.id, challenge.id, User.superUser)
      val challenges = this.service.listVirtualChildren(project.id, User.superUser)
      challenges.size mustEqual 1
      challenges.head.id mustEqual challenge.id

      this.service.removeChallenge(project.id, challenge.id, User.superUser)
      val challenges2 = this.service.listVirtualChildren(project.id, User.superUser)
      challenges2.size mustEqual 0

      // cleanup
      this.challengeDAL.delete(challenge.id, User.superUser, true)
    }

    "not add challenges to non virtual project" taggedAs VirtualProjectTag in {
      val project = this.serviceManager.project
        .create(Project(-1, User.superUser.id, "VirtualProject2Test"), User.superUser)
      val challenge =
        this.challengeDAL
          .insert(this.getTestChallenge("VP_Challenge"), User.superUser)
      intercept[InvalidException] {
        this.service.addChallenge(project.id, challenge.id, User.superUser)
      }
      // cleanup
      this.serviceManager.project.delete(project.id, User.superUser, true)
    }

    "throw NotFoundException if project not found when adding challenge" taggedAs VirtualProjectTag in {
      intercept[NotFoundException] {
        this.service.addChallenge(12354, this.defaultChallenge.id, User.superUser)
      }
    }

    "throw NotFoundException if project not found when removing challenge" taggedAs VirtualProjectTag in {
      intercept[NotFoundException] {
        this.service.removeChallenge(12345, this.defaultChallenge.id, User.superUser)
      }
    }

    "not remove challenges from non virtual project" taggedAs VirtualProjectTag in {
      intercept[InvalidException] {
        this.service
          .removeChallenge(this.defaultProject.id, this.defaultChallenge.id, User.superUser)
      }
    }
  }
  override implicit val projectTestName: String = "VirtualProjectServiceSpecProject"

  def getDefaultVirtualProject: Project =
    this.serviceManager.project.retrieveByName("ActualVirtualProjectServiceSpecProject").get

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // update default project to be a virtual project
    this.serviceManager.project.create(
      Project(
        -1,
        this.defaultUser.osmProfile.id,
        "ActualVirtualProjectServiceSpecProject",
        isVirtual = Some(true)
      ),
      this.defaultUser
    )
  }
}
