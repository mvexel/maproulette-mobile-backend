// Pure local probe: compile with the unchanged ChoiceWork/MobileScopes source
// sections and pinned Play JSON/Typesafe Config dependencies. No DB or network.
import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.{Files, Paths}
import org.maproulette.auth.mobile.MobileScopes
import org.maproulette.provider.choice.ChoiceWork
import play.api.libs.json._
import scala.jdk.CollectionConverters._

object StagePendingProbe extends App {
  val fixture = Files.readAllBytes(Paths.get(args(0)))
  val hash = java.security.MessageDigest.getInstance("SHA-256")
    .digest(fixture).map(b => f"${b & 0xff}%02x").mkString
  require(hash == "86b2cc388f7b90d7a44e417da83bc1b379cf9b75929cefb20b937c77b5a6483a")
  val payload = (Json.parse(fixture) \ "cooperativeWork").as[JsObject]
  val work = ChoiceWork.validate(payload).fold(errors => sys.error(errors.mkString("; ")), identity)
  assert(work.element == "node/14178183367" && work.liveMissingQuestions)
  assert(work.questions.map(_.id) == List("door-type", "automatic-opening"))
  assert(work.questions.map(_.expect) == List(Map("door" -> None), Map("automatic_door" -> None)))
  assert(work.questions.forall(_.holds(Map("entrance" -> "main"))))
  assert(!work.questions.head.holds(Map("door" -> "hinged")))
  val questions = (payload \ "questions").as[JsArray].value
  val unsafe = questions.head.as[JsObject] ++ Json.obj("expect" -> Json.obj("door" -> "hinged"))
  assert(ChoiceWork.validate(payload ++ Json.obj("questions" -> JsArray(questions.updated(0, unsafe))))
    .left.toOption.get.contains("liveMissingQuestions: every question must guard only absent tags"))
  println(s"PASS exact fixture through ChoiceWork.validate SHA256=$hash; absent guards enforced")

  val values = ConfigFactory.parseMap(Map(
    "MR_PUBLIC_ORIGIN" -> "https://mr-stage.osm.lol",
    "MOBILE_OAUTH_CALLBACK_URI" -> "https://mr-stage.osm.lol/oauth/mobile/callback",
    "MR_MOBILE_ADMIN_ORIGIN" -> "https://admin.mr-stage.osm.lol",
    "MR_MOBILE_ADMIN_CALLBACK_URI" -> "https://admin.mr-stage.osm.lol/callback"
  ).asJava)
  def config(file: String, enabled: Option[Boolean]) = ConfigFactory.parseFile(new File(file))
    .withFallback(enabled.fold(ConfigFactory.empty())(v => ConfigFactory.parseString(s"MR_MOBILE_GUESTS_ENABLED=$v")))
    .withFallback(values).resolve()
  val shared = config("conf/mobile-field.conf", None)
  assert(!shared.getBoolean("mobileOAuth.guests.enabled"))
  assert(!shared.getConfigList("mobileOAuth.clients").asScala.exists(_.getString("id") == "maproulette-ios-choice"))
  Seq(None, Some(false), Some(true)).foreach { optIn =>
    val stage = config("conf/mobile-field-stage-pending.conf", optIn)
    assert(stage.getBoolean("mobileOAuth.guests.enabled") == optIn.getOrElse(false))
    assert(stage.getBoolean("mobileOAuth.allowTaskWrites") && stage.getBoolean("mobileOAuth.writeControlEnabled"))
    assert(stage.getString("mobileOAuth.guests.mail.provider") == "none")
    val clients = stage.getConfigList("mobileOAuth.clients").asScala
    assert(clients.size == 4)
    val native = clients.find(_.getString("id") == "maproulette-ios-choice").get
    assert(native.getStringList("redirectUris").asScala.toSet == Set("lol.osm.maproulette.choice:/oauth2redirect"))
    val scopes = native.getStringList("scopes").asScala.mkString(" ")
    assert(MobileScopes.parseClient(scopes).contains(Set(MobileScopes.Read, MobileScopes.Guest)))
    assert(MobileScopes.parse(scopes).isEmpty)
    Seq("tasks:read tasks:write", "tasks:read tasks:write osm:tagfix").foreach { grant =>
      assert(!MobileScopes.parse(grant).get.subsetOf(MobileScopes.parseClient(scopes).get))
    }
  }
  println("PASS actual shared/selected HOCON: guest default off, explicit opt-in, native scopes/callback, write control retained, mail none")
}
