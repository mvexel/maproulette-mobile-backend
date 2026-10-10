package org.maproulette.auth.mobile

import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.choice.ChoiceTag
import play.api.Application
import play.api.db.Database
import play.api.libs.json.Json

/** Preview mode's draft lookup against the real schema. Runs in FrameworkMasterSuite. */
class MobileDraftLookupSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileDraftLookupSpecProject"
  private val drafts                            = new MobileDraftLookup(application.injector.instanceOf[Database])

  private def challenge(name: String, enabled: Boolean): Challenge =
    challengeDAL.insert(
      Challenge(
        -1,
        name,
        null,
        null,
        general = ChallengeGeneral(
          User.superUser.osmProfile.id,
          defaultProject.id,
          "Instruction",
          enabled = enabled
        ),
        creation = ChallengeCreation(),
        priority = ChallengePriority(),
        extra = ChallengeExtra()
      ),
      User.superUser
    )

  private def task(parent: Challenge): Task =
    taskDAL.insert(
      Task(
        -1,
        s"${parent.name}-task",
        null,
        null,
        parent.id,
        geometries = Json.obj(
          "features" -> Json.arr(
            Json.obj(
              "type"       -> "Feature",
              "geometry"   -> Json.obj("type" -> "Point", "coordinates" -> Json.arr(-111.89, 40.76)),
              "properties" -> Json.obj()
            )
          )
        )
      ),
      User.superUser
    )

  "MobileDraftLookup" should {
    "treat a disabled challenge or project as a draft, and unknown ids as not" taggedAs ChoiceTag in {
      serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
      val live                  = challenge("draftLookupLive", enabled = true)
      val draft                 = challenge("draftLookupDraft", enabled = false)
      val (liveTask, draftTask) = (task(live), task(draft))
      drafts.draft(MobileDraftRoutes.Challenge(live.id)) mustBe false
      drafts.draft(MobileDraftRoutes.Task(liveTask.id)) mustBe false
      drafts.draft(MobileDraftRoutes.Challenge(draft.id)) mustBe true
      drafts.draft(MobileDraftRoutes.Task(draftTask.id)) mustBe true
      drafts.draft(MobileDraftRoutes.Challenge(Long.MaxValue)) mustBe false
      drafts.draft(MobileDraftRoutes.Task(Long.MaxValue)) mustBe false
      serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> false), User.superUser)
      drafts.draft(MobileDraftRoutes.Task(liveTask.id)) mustBe true
      serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
    }
  }
}
