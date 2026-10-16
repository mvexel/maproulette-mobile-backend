package org.maproulette.auth.mobile

import anorm._
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

  private def task(parent: Challenge, name: String = "task"): Task =
    taskDAL.insert(
      Task(
        -1,
        s"${parent.name}-$name",
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

  // Bundle rows only: the bundle service would also lock the tasks, one lock per user.
  private def bundle(taskIds: Long*): Long =
    application.injector.instanceOf[Database].withConnection { implicit c =>
      val id = SQL"INSERT INTO bundles (owner_id, name) VALUES (${User.superUser.id}, 'lookup')"
        .executeInsert(SqlParser.scalar[Long].single)
      taskIds.foreach(task =>
        SQL"INSERT INTO task_bundles (bundle_id, task_id) VALUES ($id, $task)".executeUpdate()
      )
      id
    }

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

    "recognize multiple-choice tasks by cooperativeWork.meta.type 3, and unknown ids as not" taggedAs ChoiceTag in {
      val parent              = challenge("choiceLookup", enabled = true)
      val (choice, cooperate) = (task(parent, "choice"), task(parent, "cooperative"))
      def work(id: Long, meta: String) =
        application.injector.instanceOf[Database].withConnection { implicit c =>
          SQL("UPDATE tasks SET cooperative_work_json = {work}::JSONB WHERE id = {id}")
            .on("work" -> s"""{"meta":$meta}""", "id" -> id)
            .executeUpdate()
        }
      work(choice.id, """{"version":2,"type":3,"choiceVersion":1}""")
      work(cooperate.id, """{"version":2,"type":2}""")
      drafts.choiceTask(choice.id) mustBe true
      drafts.choiceTask(cooperate.id) mustBe false
      drafts.choiceTask(task(parent, "plain").id) mustBe false
      drafts.choiceTask(Long.MaxValue) mustBe false
      val choiceBundle = bundle(choice.id)
      drafts.choiceBundle(choiceBundle) mustBe true
      drafts.choiceBundle(Long.MaxValue) mustBe false
    }

    "treat a bundle or task list as a draft when any of its tasks is" taggedAs ChoiceTag in {
      serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
      val live                  = challenge("bundleLookupLive", enabled = true)
      val draft                 = challenge("bundleLookupDraft", enabled = false)
      val (liveTask, draftTask) = (task(live), task(draft))
      val liveBundle            = bundle(liveTask.id)
      val draftBundle           = bundle(liveTask.id, draftTask.id)
      drafts.draft(MobileDraftRoutes.Bundle(liveBundle)) mustBe false
      drafts.draft(MobileDraftRoutes.Bundle(draftBundle)) mustBe true
      drafts.draft(MobileDraftRoutes.Bundle(Long.MaxValue)) mustBe false
      drafts.choiceBundle(liveBundle) mustBe false
      drafts.draftTasks(Seq(liveTask.id)) mustBe false
      drafts.draftTasks(Seq(liveTask.id, draftTask.id)) mustBe true
      drafts.draftTasks(Seq(Long.MaxValue)) mustBe false
      drafts.draftTasks(Nil) mustBe false
    }
  }
}
