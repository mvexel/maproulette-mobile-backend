package org.maproulette.provider.choice

import anorm._
import org.maproulette.framework.model._
import org.maproulette.framework.util.FrameworkHelper
import play.api.Application
import play.api.db.Database
import play.api.libs.json._

/**
  * The admin's project picker and Campaigns list reads against a real database. Runs in
  * FrameworkMasterSuite (tag "choice").
  */
class MobileCampaignsSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String                         = "MobileCampaignsSpecProject"
  private val db                                                        = application.injector.instanceOf[Database]
  private val repo                                                      = new MobileCampaignsRepository(db)
  private var other: Project                                            = _
  private var listed, unpublished, draft, offProjectCampaign: Challenge = _

  private def choiceCampaign(name: String, projectId: Long, enabled: Boolean, tagged: Boolean) = {
    val challenge = createChallengeStructure(name, projectId, 4)
    db.withConnection { implicit c =>
      SQL"""UPDATE challenges SET cooperative_type = 3, enabled = $enabled
            WHERE id = ${challenge.id}""".executeUpdate()
      if (tagged) {
        SQL"""INSERT INTO tags (name) SELECT 'mobile-survey-v1'
              WHERE NOT EXISTS (SELECT 1 FROM tags WHERE lower(name) = 'mobile-survey-v1')"""
          .execute()
        SQL"""INSERT INTO tags_on_challenges (challenge_id, tag_id)
              SELECT ${challenge.id}, id FROM tags WHERE lower(name) = 'mobile-survey-v1'"""
          .execute()
      }
    }
    challenge
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    serviceManager.project.update(defaultProject.id, Json.obj("enabled" -> true), User.superUser)
    listed = choiceCampaign("campaignListed", defaultProject.id, enabled = true, tagged = true)
    unpublished =
      choiceCampaign("campaignUntagged", defaultProject.id, enabled = true, tagged = false)
    draft = choiceCampaign("campaignDraft", defaultProject.id, enabled = false, tagged = true)
    other = serviceManager.project
      .create(Project(-1, defaultUser.osmProfile.id, "MobileCampaignsSpecOff"), defaultUser)
    serviceManager.project.update(other.id, Json.obj("enabled" -> false), User.superUser)
    offProjectCampaign = choiceCampaign("campaignOff", other.id, enabled = true, tagged = true)
    db.withConnection { implicit c =>
      val ids = SQL"SELECT id FROM tasks WHERE parent_id = ${listed.id} ORDER BY id"
        .as(SqlParser.long("id").*)
      SQL"UPDATE tasks SET status = 1 WHERE id = ${ids(0)}".executeUpdate()
      SQL"UPDATE tasks SET status = 2 WHERE id = ${ids(1)}".executeUpdate()
      SQL"UPDATE tasks SET status = 6 WHERE id = ${ids(2)}".executeUpdate()
      SQL"UPDATE tasks SET status = 4 WHERE id = ${ids(3)}".executeUpdate()
    }
  }

  override protected def afterAll(): Unit = {
    serviceManager.project.delete(other.id, User.superUser, true)
    super.afterAll()
  }

  "Admin campaigns" should {
    "list only choice campaigns with their in-app state and progress" taggedAs ChoiceTag in {
      val byId = repo.campaigns().map(c => c.id -> c).toMap
      byId.keySet must contain allOf (listed.id, unpublished.id, draft.id, offProjectCampaign.id)
      byId.keySet must not contain defaultChallenge.id
      byId(listed.id).inApp mustBe "listed"
      byId(unpublished.id).inApp mustBe "not_published"
      byId(draft.id).inApp mustBe "draft"
      byId(offProjectCampaign.id).inApp mustBe "project_off"
      val progress = byId(listed.id)
      (progress.tasks, progress.checked, progress.needsAnotherLook) mustBe ((3, 2, 1))
      progress.projectId mustBe defaultProject.id
      repo.campaign(listed.id).map(_.name) mustBe Some("campaignListed")
      repo.campaign(defaultChallenge.id) mustBe None
    }

    "count a project's campaigns and listed ones" taggedAs ChoiceTag in {
      val mine = repo.project(defaultProject.id).get
      (mine.campaigns, mine.listed, mine.enabled) mustBe ((3, 1, true))
      val off = repo.project(other.id).get
      (off.campaigns, off.listed, off.enabled) mustBe ((1, 0, false))
      repo.publishedIn(other.id).map(_.id) mustBe Seq(offProjectCampaign.id)
      repo.publishedIn(defaultProject.id).map(_.id) mustBe Seq(listed.id)
      repo.projectNameTaken(projectTestName.toLowerCase) mustBe true
      repo.projectNameTaken("MobileCampaignsSpecNobody") mustBe false
    }

    "validate a new project" taggedAs ChoiceTag in {
      MobileCampaigns.parseProject(Json.obj("name" -> "slc-bus", "displayName" -> " SLC buses ")) mustBe
        Right("slc-bus" -> "SLC buses")
      MobileCampaigns.parseProject(Json.obj("name" -> "has space", "displayName" -> "")) mustBe
        Left(Seq("name: 1–100 letters, digits, '.', '_' or '-'", "displayName: 1–100 characters"))
      MobileCampaigns.parseProject(Json.obj("name" -> "x")).isLeft mustBe true
    }
  }
}
