package org.maproulette.provider.choice.claim

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.{JsObject, Json}
import play.api.libs.ws.WSClient
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.control.NonFatal

/**
  * Deferred sign-up email settings (`mobileOAuth.guests.mail`). The provider is pluggable:
  * `postmark` sends through Postmark's HTTP API with a server token from the environment, `log`
  * logs that a message would have been sent (development only; never the address or the link),
  * and `none` (the default) sends nothing, so the email route answers 503.
  */
@Singleton
class ClaimMailSettings @Inject() (configuration: Configuration) {
  private def string(path: String): Option[String] =
    configuration.getOptional[String](path).map(_.trim).filter(_.nonEmpty)

  val provider: String = string("mobileOAuth.guests.mail.provider").getOrElse("none")
  val from: String =
    string("mobileOAuth.guests.mail.from").getOrElse("Street Tally <hello@streettally.osm.lol>")

  /** Origin of the claim page; links are `<origin>/claim#t=<token>`. */
  val claimOrigin: String = string("mobileOAuth.guests.mail.claimOrigin")
    .getOrElse("https://streettally.osm.lol")
    .stripSuffix("/")
  val postmarkToken: Option[String] = string("mobileOAuth.guests.mail.postmark.serverToken")
  val postmarkStream: String =
    string("mobileOAuth.guests.mail.postmark.messageStream").getOrElse("outbound")
}

/** One rendered email. `tag` names the template, for provider statistics; it holds no personal data. */
case class ClaimMessage(to: String, subject: String, text: String, html: String, tag: String)

@com.google.inject.ImplementedBy(classOf[ConfiguredClaimMailer])
trait ClaimMailer {

  /** False when no provider is configured: the email route then answers 503 mail_unavailable. */
  def available: Boolean

  /** Right on acceptance by the provider; Left with a short reason that holds no personal data. */
  def send(message: ClaimMessage): Future[Either[String, Unit]]
}

object PostmarkClaimMailer {
  val Endpoint = "https://api.postmarkapp.com/email"

  /** The request body. Open and link tracking stay off: links carry the claim token. */
  def body(message: ClaimMessage, from: String, stream: String): JsObject =
    Json.obj(
      "From"          -> from,
      "To"            -> message.to,
      "Subject"       -> message.subject,
      "TextBody"      -> message.text,
      "HtmlBody"      -> message.html,
      "Tag"           -> message.tag,
      "MessageStream" -> stream,
      "TrackOpens"    -> false,
      "TrackLinks"    -> "None"
    )
}

class PostmarkClaimMailer(ws: WSClient, token: String, from: String, stream: String)(
    implicit ec: ExecutionContext
) extends ClaimMailer {
  def available: Boolean = true

  def send(message: ClaimMessage): Future[Either[String, Unit]] =
    ws.url(PostmarkClaimMailer.Endpoint)
      .withRequestTimeout(15.seconds)
      .withHttpHeaders(
        "Accept"                  -> "application/json",
        "X-Postmark-Server-Token" -> token
      )
      .post(PostmarkClaimMailer.body(message, from, stream))
      .map { response =>
        val code = scala.util.Try((response.json \ "ErrorCode").as[Int]).getOrElse(-1)
        if (response.status == 200 && code == 0) Right(())
        // Postmark's ErrorCode identifies the problem without echoing the address.
        else Left(s"postmark_${response.status}_$code")
      }
      .recover { case NonFatal(e) => Left(s"postmark_${e.getClass.getSimpleName}") }
}

/** Development: records that a message was sent, without its address, subject or link. */
class LogClaimMailer extends ClaimMailer {
  private val logger     = play.api.Logger(getClass)
  def available: Boolean = true
  def send(message: ClaimMessage): Future[Either[String, Unit]] = {
    logger.info(s"Guest email not sent (log provider): ${message.tag}")
    Future.successful(Right(()))
  }
}

object NoClaimMailer extends ClaimMailer {
  def available: Boolean = false
  def send(message: ClaimMessage): Future[Either[String, Unit]] =
    Future.successful(Left("mail_unavailable"))
}

@Singleton
class ConfiguredClaimMailer @Inject() (settings: ClaimMailSettings, ws: WSClient)(
    implicit ec: ExecutionContext
) extends ClaimMailer {
  private val logger = play.api.Logger(getClass)
  private val delegate: ClaimMailer = settings.provider match {
    case "postmark" =>
      settings.postmarkToken match {
        case Some(token) =>
          new PostmarkClaimMailer(ws, token, settings.from, settings.postmarkStream)
        case None =>
          logger.error(
            "mobileOAuth.guests.mail.provider is postmark but MR_POSTMARK_SERVER_TOKEN is unset; guest email is off"
          )
          NoClaimMailer
      }
    case "log"  => new LogClaimMailer
    case "none" => NoClaimMailer
    case other =>
      logger.error(s"Unknown mobileOAuth.guests.mail.provider '$other'; guest email is off")
      NoClaimMailer
  }
  def available: Boolean                                        = delegate.available
  def send(message: ClaimMessage): Future[Either[String, Unit]] = delegate.send(message)
}

/**
  * The deferred sign-up emails (brand/email in the project files; copies in `conf/mobile-email`).
  * Placeholders are `{{name}}`; every value is formatted by the caller, so templates hold no logic.
  * Values are HTML-escaped in the HTML version.
  */
object ClaimEmails {
  val Claim     = "claim"
  val Reminder  = "reminder"
  val Expiry    = "expiry"
  val Reauth    = "reauth"
  val Published = "published"

  private def subjectTemplate(name: String): String = name match {
    case Claim     => "Put your {{savedAnswersText}} from {{campaignName}} on the map"
    case Reminder  => "{{reminderSubject}}"
    case Expiry    => "Your {{campaignName}} answers were removed"
    case Reauth    => "One more step to put your {{campaignName}} answers on the map"
    case Published => "Your {{publishedStopsText}} are on the map"
  }

  private val cache = new java.util.concurrent.ConcurrentHashMap[String, String]()
  private def load(file: String): String =
    cache.computeIfAbsent(
      file,
      f => {
        val stream = Option(getClass.getClassLoader.getResourceAsStream(s"mobile-email/$f"))
          .getOrElse(throw new IllegalStateException(s"Missing email template $f"))
        try Source.fromInputStream(stream, "UTF-8").mkString
        finally stream.close()
      }
    )

  private val Placeholder = """\{\{([A-Za-z]+)\}\}""".r

  def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")

  /** Fills every placeholder; a missing value is a programming error, so it throws. */
  def fill(template: String, values: Map[String, String], html: Boolean): String =
    Placeholder.replaceAllIn(
      template,
      m => {
        val value = values.getOrElse(
          m.group(1),
          throw new IllegalArgumentException(s"No value for {{${m.group(1)}}}")
        )
        scala.util.matching.Regex.quoteReplacement(if (html) escape(value) else value)
      }
    )

  def render(name: String, to: String, values: Map[String, String]): ClaimMessage =
    ClaimMessage(
      to,
      fill(subjectTemplate(name), values, html = false),
      fill(load(s"$name.txt"), values, html = false),
      fill(load(s"$name.html"), values, html = true),
      s"guest-$name"
    )

  /** "14 bus stops", "1 bus stop". */
  def count(n: Int, one: String, many: String): String = s"$n ${if (n == 1) one else many}"
}
