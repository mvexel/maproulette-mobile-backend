package org.maproulette.auth.mobile

import org.maproulette.framework.service.UserService
import org.maproulette.framework.util.FrameworkHelper
import org.maproulette.provider.choice.ChoiceTag
import play.api.{Application, Configuration}

/**
  * The stage service key's user (SK) against a real database: created once, a super-user, never a
  * real OSM account. Runs in FrameworkMasterSuite (tag "choice").
  */
class MobileServiceUserSpec(implicit val application: Application) extends FrameworkHelper {
  override implicit val projectTestName: String = "MobileServiceUserSpecProject"

  private def stageKey() =
    new MobileStageServiceKey(
      Configuration.from(
        Map(
          "mobileOAuth.serviceKey.sha256" -> ("ab" * 32),
          "mobileOAuth.deployment"        -> "stage",
          "maproulette.publicOrigin"      -> MobileServiceKey.StageOrigin
        )
      ),
      () => application.injector.instanceOf[UserService],
      () => application.injector.instanceOf[MobileUserProvisioner]
    )

  "The stage service user" should {
    "be created once, as a super-user with the reserved OSM id" taggedAs ChoiceTag in {
      val user = stageKey().user().get
      user.id must be > 0L
      user.osmProfile.id mustBe MobileServiceKey.ServiceOsmId
      user.osmProfile.displayName mustBe MobileServiceKey.ServiceName
      application.injector.instanceOf[MobileAdminCheck].isAdmin(user) mustBe true
      stageKey().user().map(_.id) mustBe Some(user.id)
    }
  }
}
