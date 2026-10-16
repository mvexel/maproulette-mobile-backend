package org.maproulette.auth.mobile

import akka.stream.Materializer
import javax.inject.Inject
import org.maproulette.auth.mobile.guest.{MobileGuest, MobileGuestAuth}
import org.maproulette.framework.model.User
import org.maproulette.framework.service.UserService
import org.maproulette.session.SessionManager
import play.api.libs.json.Json
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{Filter, RequestHeader, Result, Results}
import scala.concurrent.{ExecutionContext, Future}

object MobileBearerIdentity {
  val UserKey: TypedKey[User] = TypedKey[User]("mobile-oauth-user")
  // The grant's scopes and family, for controllers that need more than tasks:write.
  val ScopesKey: TypedKey[Set[String]] = TypedKey[Set[String]]("mobile-oauth-scopes")
  val FamilyKey: TypedKey[String]      = TypedKey[String]("mobile-oauth-family")
  val ClientKey: TypedKey[String]      = TypedKey[String]("mobile-oauth-client")
  // Set instead of UserKey for a deferred sign-up guest; a guest is never a MapRoulette user.
  val GuestKey: TypedKey[MobileGuest] = TypedKey[MobileGuest]("mobile-guest")
}

/**
  * Routes for `mobile:admin` grants, and only for them: the fork's admin API, plus the exact stock
  * routes the admin app's challenge creator needs. Nothing else, not even the app read routes.
  */
object MobileAdminRoutes {
  private val challenge = "/api/v2/challenge/[0-9]+"
  val Methods           = Set("GET", "POST", "PATCH", "PUT", "DELETE")

  def adminApi(path: String): Boolean =
    path.matches("/api/v2/mobile-admin(/[A-Za-z0-9_-][A-Za-z0-9._-]*)+")

  /** Method and path only; [[permits]] also checks the `addFileTasks` query. */
  def matches(method: String, path: String): Boolean =
    if (adminApi(path)) Methods.contains(method)
    else
      method match {
        case "GET" =>
          path == "/oauth/mobile/me" || path.matches(challenge) ||
            path.matches(s"$challenge/tasks") || path.matches("/api/v2/task/[0-9]+")
        case "POST" => path == "/api/v2/challenge"
        case "PUT"  => path.matches(challenge) || path.matches(s"$challenge/addFileTasks")
        case _      => false
      }

  /** `addFileTasks` only line by line with a per-line report: never removeUnmatched. */
  def permits(method: String, path: String, query: Map[String, Seq[String]]): Boolean =
    matches(method, path) && (!path.endsWith("/addFileTasks") ||
      query == Map("lineByLine" -> Seq("true"), "report" -> Seq("true")))

  /** Stock writes, which the bearer filter records in the admin audit log. */
  def stockWrite(method: String, path: String): Boolean = method != "GET" && !adminApi(path)
}

/**
  * Routes for `mobile:organize` grants (D8): the stock challenge reads of [[MobileAdminRoutes]]
  * and the campaign part of the admin API. Organizers write only through routes whose controllers
  * check the project ([[MobileAdminCheck.can]]), never through stock challenge writes. No clients,
  * audit, organizers, project creation or write switch changes.
  */
object MobileOrganizeRoutes {
  private val challenge = "/api/v2/mobile-admin/challenges/[0-9]+"
  private val wizard    = "/api/v2/mobile-admin/wizard"

  def adminApi(method: String, path: String): Boolean = method match {
    case "GET" =>
      Set(
        "/api/v2/mobile-admin/campaigns",
        "/api/v2/mobile-admin/projects",
        "/api/v2/mobile-admin/write-policy",
        s"$wizard/question-sets",
        s"$wizard/counts"
      ).contains(path) || path.matches(s"$challenge/(live|results|queue/export)") ||
        path.matches(s"$wizard/question-sets/[a-z0-9-]{1,32}/features")
    case "PUT" =>
      path.matches("/api/v2/mobile-admin/projects/[0-9]+/enabled") ||
        path.matches(s"$challenge/live")
    case "POST" =>
      path.matches(s"$challenge/(live/rotate|queue)") || path == s"$wizard/campaigns" ||
        path.matches(s"$wizard/campaigns/[0-9]+/publish")
    case _ => false
  }

  def permits(method: String, path: String, query: Map[String, Seq[String]]): Boolean =
    MobileAdminRoutes.permits(method, path, query) &&
      (if (MobileAdminRoutes.adminApi(path)) adminApi(method, path) else method == "GET")
}

/** An explicit route allowlist: several legacy GET routes mutate data or disclose API keys. */
object MobileReadRoutes {
  private val number = "[+-]?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
  private val box    = s"$number/$number/$number/$number"
  private val reads = Seq(
    "/api/v2/challenges/extendedFind",
    "/api/v2/challenge/[0-9]+",
    "/api/v2/challenge/[0-9]+/tags",
    "/api/v2/challenge/[0-9]+/tasks",
    "/api/v2/task/[0-9]+",
    "/api/v2/task/[0-9]+/choice/check",
    s"/api/v2/tasks/box/$box",
    "/api/v2/mobile-claim/[0-9]+",
    "/oauth/mobile/me"
  ).map(_.r)

  def permits(method: String, path: String): Boolean =
    (method == "GET" && reads.exists(_.pattern.matcher(path).matches())) ||
      (method == "PUT" && path.matches(s"/api/v2/markers/box/$box"))

  /** Reads that take no query string or body. */
  def bare(method: String, path: String): Boolean =
    method == "GET" && (path.matches("/api/v2/task/[0-9]+/choice/check") ||
      path.matches("/api/v2/mobile-claim/[0-9]+"))
}

/**
  * Routes for guest access tokens (deferred sign-up), and only for them: the app discovery reads,
  * the choice check, pending answers and the guest's own routes. No task writes, no identity
  * endpoint, no admin routes.
  */
object MobileGuestRoutes {
  private val pending = "/api/v2/task/[0-9]+/choice/pending"
  private val list    = "/api/v2/mobile-guest/pending"

  /** Routes that only guests reach. None of them writes OSM or a task status. */
  def own(method: String, path: String): Boolean = method match {
    case "GET"    => path == "/api/v2/mobile-guest/me" || path == list
    case "POST"   => path.matches(pending)
    case "DELETE" => path == "/api/v2/mobile-guest" || path.matches(pending)
    case "PUT"    => email(method, path)
    case _        => false
  }

  /** Setting the claim email or the reminder switch (B4): a JSON body, no query string. */
  def email(method: String, path: String): Boolean =
    method == "PUT" && (path == "/api/v2/mobile-guest/email" || path == "/api/v2/mobile-guest/reminders")

  /** The emailed links' routes (B4, B5 preview): a claim token in a JSON body, no credential. */
  def claimToken(method: String, path: String): Boolean =
    method == "POST" && Set(
      "/api/v2/mobile-claim/preview",
      "/api/v2/mobile-claim/delete",
      "/api/v2/mobile-claim/stop-reminders"
    ).contains(path)

  def permits(method: String, path: String): Boolean =
    own(method, path) || (MobileReadRoutes.permits(method, path) && path != "/oauth/mobile/me" &&
      !path.startsWith("/api/v2/mobile-claim/"))

  /** The one guest route with a body: a pending answer, shaped like a choice submission. */
  def takesBody(method: String, path: String): Boolean = method == "POST" && path.matches(pending)

  /** Whether the request has the shape its guest route allows. */
  def acceptable(request: RequestHeader): Boolean =
    if (takesBody(request.method, request.path)) MobileWriteRoutes.acceptableBody(request)
    else if (email(request.method, request.path)) request.rawQueryString.isEmpty
    else if (request.method == "GET" && request.path == list)
      !request.hasBody && request.queryString.forall {
        case (key, values) => Set("limit", "after").contains(key) && values.size == 1
      }
    else if (own(request.method, request.path) || MobileReadRoutes.bare(
               request.method,
               request.path
             ))
      request.rawQueryString.isEmpty && !request.hasBody
    else true

  /** Allowed while field writes are off, for guest tokens only (checked after authentication). */
  def guestOnlyWhenDisabled(method: String, path: String): Boolean =
    method == "GET" && path.matches("/api/v2/task/[0-9]+/choice/check")
}

/**
  * The claim writes (deferred sign-up B5) and the claim page's client (D13). While the field write
  * switch is off, these two routes are the only writes allowed, and only for [[ClientId]], whose
  * grants in turn reach no other write: the claim is recorded and waits for the publish job.
  */
object MobileClaimRoutes {
  val ClientId = "maproulette-claim"

  def permits(method: String, path: String): Boolean =
    method == "POST" && (path == "/api/v2/mobile-claim" ||
      path.matches("/api/v2/mobile-claim/[0-9]+/retry"))
}

/**
  * Task lifecycle writes for grants with `tasks:write`. Only the bare route is allowed: no query
  * string (which would carry requestReview or tags) and no body (completion responses). Status
  * codes are limited to Fixed, False positive, Already fixed and Too hard. Mobile clients lock late
  * (start immediately before the status write), so refreshLock is not allowed here; desktop
  * editors get it, and the extras above, through [[MobileEditorRoutes]] with `tasks:edit`.
  */
object MobileWriteRoutes {
  private val task       = "/api/v2/task/[0-9]+"
  val MaxChoiceBodyBytes = 2048

  def permits(method: String, path: String): Boolean = method match {
    case "GET"  => path.matches(s"$task/(start|release)")
    case "POST" => path.matches(s"$task/(skip|choice)") || MobileClaimRoutes.permits(method, path)
    case "PUT"  => path.matches(s"$task/[1256]")
    case _      => false
  }

  /** The only write that carries a body: a small JSON choice submission. */
  def takesBody(method: String, path: String): Boolean =
    method == "POST" && (path.matches(s"$task/choice") || MobileClaimRoutes.permits(method, path))

  def acceptableBody(request: RequestHeader): Boolean = {
    val contentType = request.headers
      .get("Content-Type")
      .map(_.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))
    val length = request.headers.getAll("Content-Length") match {
      case Seq(value) if value.matches("[0-9]{1,5}") => Some(value.toInt)
      case _                                         => None
    }
    request.rawQueryString.isEmpty &&
    request.headers.get("Transfer-Encoding").isEmpty &&
    contentType.exists(Set("application/json", "application/json;charset=utf-8").contains) &&
    length.exists(value => value > 0 && value <= MaxChoiceBodyBytes)
  }
}

/**
  * Desktop editor writes for grants with `tasks:edit` (which implies `tasks:write`): the status
  * write with its review request, tags and completion responses, task comments, refreshLock (an
  * editing session outlasts the lock) and the changeset link, plus the task bundle routes: create,
  * read, update, unbundle, delete, lock and unlock, bundle status and bundle comments (not bundle
  * review). Each route takes only the listed query keys, each at most once except `taskIds` (at
  * most [[MaxBundleTasks]] values), and only the status writes, the comments and bundle create take
  * a JSON body of at most [[MaxBodyBytes]]. A bare status write stays a [[MobileWriteRoutes]]
  * lifecycle write.
  */
object MobileEditorRoutes {
  private val task   = "/api/v2/task/[0-9]+"
  val MaxBodyBytes   = 16384
  val MaxValueLength = 1024

  private def status(method: String, path: String) =
    method == "PUT" && path.matches(s"$task/[1256]")
  private def comment(method: String, path: String) =
    method == "POST" && path.matches(s"$task/comment")

  private def changeset(method: String, path: String) =
    method == "PUT" && path.matches(s"$task/changeset")
  private val digits: String => Boolean = _.matches("[0-9]{1,18}")

  private val bundle = "/api/v2/taskBundle/[0-9]+"
  // Same cap as the web UI's bundle limit.
  val MaxBundleTasks = 50

  private def bundleCreate(method: String, path: String) =
    method == "POST" && path == "/api/v2/taskBundle"
  private def bundleStatus(method: String, path: String) =
    method == "PUT" && path.matches(s"$bundle/[1256]")
  private def bundleComment(method: String, path: String) =
    method == "POST" && path.matches(s"$bundle/comment")
  // Routes whose only query is the required `taskIds` list.
  private def bundleMembers(method: String, path: String) =
    method == "POST" && path.matches(s"$bundle/(update|unbundle)") ||
      method == "POST" && path == "/api/v2/task/bundle/unlock"
  private def lockBundle(method: String, path: String) =
    method == "PUT" && path.matches(s"$task/lockBundle")
  private def bundleBare(method: String, path: String) =
    (method == "POST" || method == "DELETE") && path.matches(bundle)

  private def bundleRoute(method: String, path: String) =
    bundleCreate(method, path) || bundleStatus(method, path) || bundleComment(method, path) ||
      bundleMembers(method, path) || lockBundle(method, path) || bundleBare(method, path)

  def permits(method: String, path: String): Boolean =
    status(method, path) || comment(method, path) || changeset(method, path) ||
      method == "GET" && path.matches(s"$task/refreshLock") || bundleRoute(method, path)

  /** True when the request needs `tasks:edit`: an editor-only route, or a status write with extras. */
  def requires(request: RequestHeader): Boolean =
    permits(request.method, request.path) &&
      (!MobileWriteRoutes.permits(request.method, request.path) ||
        request.rawQueryString.nonEmpty || request.hasBody)

  private def query(request: RequestHeader): Boolean = {
    val (method, path) = (request.method, request.path)
    // Only "true": "false" would skip a review the user's settings make mandatory
    // (docs/upstream-issues.md §5).
    val review: (String, String => Boolean) = "requestReview" -> Set("true")
    val allowed: Map[String, String => Boolean] =
      if (status(method, path)) Map(review, "tags" -> (_ => true))
      else if (bundleStatus(method, path))
        Map("primaryId" -> digits, review, "tags" -> (_ => true))
      else if (comment(method, path) || bundleComment(method, path)) Map("actionId" -> digits)
      else if (changeset(method, path)) Map("changesetId" -> digits)
      else if (bundleMembers(method, path) || lockBundle(method, path)) Map("taskIds" -> digits)
      else Map.empty
    val required =
      if (bundleStatus(method, path)) Set("primaryId")
      else if (bundleMembers(method, path)) Set("taskIds")
      else Set.empty[String]
    def values(key: String, given: Seq[String]) =
      given.nonEmpty && given.size <= (if (key == "taskIds") MaxBundleTasks else 1)
    required.subsetOf(request.queryString.keySet) && request.queryString.forall {
      case (key, given) =>
        values(key, given) && allowed.get(key).exists { valid =>
          given.forall(value => value.length <= MaxValueLength && valid(value))
        }
    }
  }

  private def takesBody(method: String, path: String) =
    status(method, path) || comment(method, path) || bundleStatus(method, path) ||
      bundleComment(method, path) || bundleCreate(method, path)

  // Comments and bundle create need their JSON; status writes may omit completion responses.
  private def needsBody(method: String, path: String) =
    comment(method, path) || bundleComment(method, path) || bundleCreate(method, path)

  private def body(request: RequestHeader): Boolean =
    if (!takesBody(request.method, request.path)) !request.hasBody
    else if (!request.hasBody) !needsBody(request.method, request.path)
    else {
      val contentType = request.headers
        .get("Content-Type")
        .map(_.toLowerCase(java.util.Locale.ROOT).replace(" ", ""))
      val length = request.headers.getAll("Content-Length") match {
        case Seq(value) if value.matches("[0-9]{1,5}") => Some(value.toInt)
        case _                                         => None
      }
      request.headers.get("Transfer-Encoding").isEmpty &&
      contentType.exists(Set("application/json", "application/json;charset=utf-8").contains) &&
      length.exists(value => value > 0 && value <= MaxBodyBytes)
    }

  def acceptable(request: RequestHeader): Boolean = query(request) && body(request)
}

/** The live progress page (BACKLOG #12): its public read and its admin settings routes. */
object MobileLiveRoutes {
  def public(path: String): Boolean   = path.matches("/api/v2/live/[A-Za-z0-9_-]{43}")
  def settings(path: String): Boolean = path.matches("/api/v2/mobile-admin/challenges/[0-9]+/live")
  def rotate(path: String): Boolean =
    path.matches("/api/v2/mobile-admin/challenges/[0-9]+/live/rotate")
}

/**
  * The review page (H1a): its read, by the live page token, and the per-place marks. Marks write
  * only the fork's review tables, never a task or OSM, so app grants with `tasks:read` reach them,
  * also while field writes are off. Guest and admin grants don't.
  */
object MobileReviewRoutes {
  private val page = "/api/v2/review/[A-Za-z0-9_-]{43}"

  def read(method: String, path: String): Boolean = method == "GET" && path.matches(page)

  def mark(method: String, path: String): Boolean =
    (method == "PUT" || method == "DELETE") &&
      path.matches(s"$page/places/[0-9]{1,18}/(skip|needs-visit)")

  def permits(method: String, path: String): Boolean = read(method, path) || mark(method, path)
}

/** During a field deployment's disabled phase, expose only the mobile login,
  * admin control, and known read routes. This also closes legacy session and
  * API-key write paths while the policy is off.
  */
object MobileFieldRoutes {
  private val discovery = Set(
    "/api/v2/challenges/tags",
    "/api/v2/challenges/find",
    "/api/v2/challenges/search",
    "/api/v2/challenges/extendedFind"
  )

  def permitsWhenDisabled(request: RequestHeader): Boolean = {
    // Challenge preparation is an admin action, not a mapper task or OSM edit.
    // The bearer filter below validates the grant and super-user before forwarding.
    val bearer = request.headers
      .getAll("Authorization")
      .exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("bearer"))
    val adminSetup = bearer &&
      MobileAdminRoutes.permits(request.method, request.path, request.queryString) &&
      MobileAdminRoutes.stockWrite(request.method, request.path)
    adminSetup || (request.method match {
      case "OPTIONS" => true
      case "GET" =>
        request.path == "/ping" || MobileLiveRoutes.public(request.path) ||
          MobileReviewRoutes.read(request.method, request.path) ||
          Set("/oauth/mobile/authorize", "/oauth/mobile/callback", "/oauth/mobile/me")
            .contains(request.path) ||
          // Guests neither write OSM nor change task status. The choice check is for guest
          // tokens only while writes are off; the bearer filter refuses it to app grants.
          MobileGuestRoutes.own(request.method, request.path) ||
          (bearer && MobileGuestRoutes.guestOnlyWhenDisabled(request.method, request.path)) ||
          discovery.contains(request.path) ||
          request.path.matches("/api/v2/task/[0-9]+/tags") ||
          (!request.path.matches("/api/v2/task/[0-9]+/choice/check") &&
            MobileReadRoutes.permits(request.method, request.path)) ||
          MobileAdminRoutes.permits(request.method, request.path, request.queryString)
      case "POST" =>
        Set(
          "/oauth/mobile/consent",
          "/oauth/mobile/token",
          "/oauth/mobile/revoke",
          "/oauth/mobile/guest",
          "/api/v2/mobile-admin/clients",
          "/api/v2/mobile-admin/projects",
          "/api/v2/mobile-admin/organizers"
        ).contains(request.path) || MobileGuestRoutes.own(request.method, request.path) ||
          MobileLiveRoutes.rotate(request.path) ||
          // The confirm queue reads OSM and writes only our tables (H1b).
          request.path.matches("/api/v2/mobile-admin/challenges/[0-9]+/queue") ||
          // The wizard prepares and lists campaigns: no task or OSM write. Organizers' grants are
          // still refused below while the switch is off.
          request.path == "/api/v2/mobile-admin/wizard/campaigns" ||
          request.path.matches("/api/v2/mobile-admin/wizard/campaigns/[0-9]+/publish") ||
          MobileGuestRoutes.claimToken(request.method, request.path) ||
          // D13: the bearer filter below lets only the claim page's grant through.
          (bearer && MobileClaimRoutes.permits(request.method, request.path))
      case "PATCH" => request.path.matches("/api/v2/mobile-admin/clients/[A-Za-z0-9._-]+")
      // Turning a project on or off lists or hides campaigns; it is not a task or OSM write.
      case "PUT" =>
        request.path == "/api/v2/mobile-admin/write-policy" ||
          request.path.matches("/api/v2/mobile-admin/projects/[0-9]+/enabled") ||
          // The live page's settings: who may see progress, not a task or OSM write.
          MobileLiveRoutes.settings(request.path) ||
          MobileGuestRoutes.email(request.method, request.path) ||
          // Review marks write only the review tables.
          MobileReviewRoutes.mark(request.method, request.path)
      case "DELETE" =>
        // Organizers: our table and upstream project grants, never a task or OSM.
        request.path.matches("/api/v2/mobile-admin/organizers/[0-9]+(/projects/[0-9]+)?") ||
          MobileGuestRoutes.own(request.method, request.path) ||
          MobileReviewRoutes.mark(request.method, request.path)
      case _ => false
    })
  }
}

/** Disabled/legacy requests pass through unchanged; mobile credentials can never fall back. */
class MobileBearerFilter @Inject() (
    settings: MobileOAuthSettings,
    oauth: MobileOAuthService,
    users: UserService,
    admins: MobileAdminCheck,
    adminRepository: MobileAdminRepository,
    writePolicy: MobileWritePolicy,
    guests: MobileGuestAuth,
    drafts: MobileDrafts
)(implicit val mat: Materializer, ec: ExecutionContext)
    extends Filter {
  private val logger = play.api.Logger(getClass)

  // Without guests, every challenge published: for tests of app and admin grants.
  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository,
      writePolicy: MobileWritePolicy
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) =
    this(
      settings,
      oauth,
      users,
      admins,
      adminRepository,
      writePolicy,
      MobileGuestAuth.Disabled,
      MobileDrafts.NoDrafts
    )

  // Every challenge published: for guest tests.
  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository,
      writePolicy: MobileWritePolicy,
      guests: MobileGuestAuth
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) =
    this(
      settings,
      oauth,
      users,
      admins,
      adminRepository,
      writePolicy,
      guests,
      MobileDrafts.NoDrafts
    )

  // Without guests: for preview mode tests.
  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository,
      writePolicy: MobileWritePolicy,
      drafts: MobileDrafts
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) =
    this(
      settings,
      oauth,
      users,
      admins,
      adminRepository,
      writePolicy,
      MobileGuestAuth.Disabled,
      drafts
    )

  // No admins and so no audit log: for tests of app grants only.
  def this(settings: MobileOAuthSettings, oauth: MobileOAuthService, users: UserService)(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) =
    this(
      settings,
      oauth,
      users,
      MobileAdminCheck.Nobody,
      null,
      null,
      MobileGuestAuth.Disabled,
      MobileDrafts.NoDrafts
    )

  def this(
      settings: MobileOAuthSettings,
      oauth: MobileOAuthService,
      users: UserService,
      admins: MobileAdminCheck,
      adminRepository: MobileAdminRepository
  )(
      implicit mat: Materializer,
      ec: ExecutionContext
  ) =
    this(
      settings,
      oauth,
      users,
      admins,
      adminRepository,
      null,
      MobileGuestAuth.Disabled,
      MobileDrafts.NoDrafts
    )

  private def denied(status: Int, code: String): Future[Result] = Future.successful(
    Results
      .Status(status)(Json.obj("error" -> code))
      .withHeaders("Cache-Control" -> "no-store", "WWW-Authenticate" -> "Bearer")
  )

  /**
    * Preview mode: a draft challenge and its tasks read as unknown (404) except to users who may
    * organize it, for now super-users only (never guests, `user` None). Writes to a draft are
    * refused for everyone.
    */
  private def draftGate(
      user: Option[User],
      write: Boolean,
      request: RequestHeader
  ): Future[Option[Result]] = {
    val members = MobileDraftRoutes.members(request.method, request.path, request.queryString)
    MobileDraftRoutes.target(request.method, request.path) match {
      case target if target.isDefined || members.nonEmpty =>
        Future(target.exists(drafts.draft) || drafts.draftTasks(members)).flatMap {
          case false => Future.successful(None)
          case true if !user.exists(u => admins.isAdmin(u)) =>
            Future.successful(Some(Results.NotFound.withHeaders("Cache-Control" -> "no-store")))
          case true if write => denied(403, "challenge_draft").map(Some(_))
          case true          => Future.successful(None)
        }
      case _ => Future.successful(None)
    }
  }

  private val statusWrite  = "/api/v2/task/([0-9]+)/[1256]".r
  private val bundleStatus = "/api/v2/taskBundle/([0-9]+)/[1256]".r

  /**
    * A multiple-choice task's status is set by `POST /task/:id/choice`, which makes the OSM edit and
    * dedupes submissions. A direct status write would skip both, so app grants can't make one,
    * with `tasks:write` or `tasks:edit`, on the task or on a bundle that holds one.
    */
  private def choiceGate(request: RequestHeader): Future[Option[Result]] =
    (request.method, request.path) match {
      case ("PUT", statusWrite(id))  => choiceRefusal(drafts.choiceTask(id.toLong))
      case ("PUT", bundleStatus(id)) => choiceRefusal(drafts.choiceBundle(id.toLong))
      case _                         => Future.successful(None)
    }

  private def choiceRefusal(choice: => Boolean): Future[Option[Result]] =
    Future(choice).flatMap {
      case true  => denied(403, "choice_task_status").map(Some(_))
      case false => Future.successful(None)
    }

  /**
    * Admin writes through stock routes: who, which route, and the outcome. Also recorded when the
    * action fails with an exception (status 500 and the exception class), since part of the write
    * may have been committed.
    */
  private def auditStockWrite(
      user: User,
      request: RequestHeader,
      outcome: scala.util.Try[Result]
  ): Future[Result] = {
    val after = outcome match {
      case scala.util.Success(response) => Json.obj("status" -> response.header.status)
      case scala.util.Failure(e)        => Json.obj("status" -> 500, "error" -> e.getClass.getSimpleName)
    }
    Future(adminRepository.record(user.id, s"stock.${request.method}", request.path, Some(after)))
      .recover {
        case e: Exception =>
          // The write already happened; losing its audit entry must be visible in the logs.
          logger.error(
            s"Mobile admin audit entry not recorded for ${request.method} ${request.path}: ${e.getClass.getSimpleName}"
          )
      }
      .flatMap(_ => Future.fromTry(outcome))
  }

  /** D13 (3): every claim write let through while the switch is off is audited. */
  private def audit(
      user: User,
      action: String,
      request: RequestHeader,
      outcome: scala.util.Try[Result]
  ): Future[Result] = {
    val after = outcome match {
      case scala.util.Success(response) => Json.obj("status" -> response.header.status)
      case scala.util.Failure(e)        => Json.obj("status" -> 500, "error" -> e.getClass.getSimpleName)
    }
    Future(adminRepository.record(user.id, action, request.path, Some(after)))
      .recover {
        case e: Exception =>
          logger.error(
            s"Mobile audit entry $action not recorded for ${request.path}: ${e.getClass.getSimpleName}"
          )
      }
      .flatMap(_ => Future.fromTry(outcome))
  }

  def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {
    if (settings.writeControlEnabled && !MobileFieldRoutes.permitsWhenDisabled(request) &&
        !writePolicy.enabled) return denied(403, "mobile_writes_disabled")
    val authorizations = request.headers.getAll("Authorization")
    val mobile         = authorizations.exists(_.toLowerCase(java.util.Locale.ROOT).startsWith("bearer"))
    if (!settings.enabled || !mobile) return next(request)
    // Upstream no longer honours the legacy apiKey header; a bearer request carrying it, or a web
    // session, is still ambiguous and refused.
    if (authorizations.size != 1 || request.headers.get("apiKey").isDefined ||
        request.session.get(SessionManager.KEY_TOKEN_HASH).isDefined ||
        request.session.get(SessionManager.KEY_USER_ID).isDefined)
      return denied(401, "invalid_token")
    val parts = authorizations.head.split(" ", -1)
    if (parts.length != 2 || !parts(0).equalsIgnoreCase("Bearer") ||
        !parts(1).matches("[A-Za-z0-9_-]{32,256}")) return denied(401, "invalid_token")
    val editor = MobileEditorRoutes.requires(request)
    val write  = MobileWriteRoutes.permits(request.method, request.path) || editor
    val read = MobileReadRoutes.permits(request.method, request.path) ||
      MobileReviewRoutes.permits(request.method, request.path)
    val admin       = MobileAdminRoutes.permits(request.method, request.path, request.queryString)
    val organize    = MobileOrganizeRoutes.permits(request.method, request.path, request.queryString)
    val guest       = guests.enabled && MobileGuestRoutes.permits(request.method, request.path)
    val claimRoute  = MobileClaimRoutes.permits(request.method, request.path)
    val switchedOff = write && settings.writeControlEnabled && !writePolicy.enabled
    if (write && !settings.allowTaskWrites || switchedOff && !claimRoute)
      return denied(403, "mobile_writes_disabled")
    if (!write && !read && !admin && !guest) return denied(403, "insufficient_scope")
    val badShape =
      if (editor) !MobileEditorRoutes.acceptable(request)
      else if (write && MobileWriteRoutes.takesBody(request.method, request.path))
        !MobileWriteRoutes.acceptableBody(request)
      else if (guest && !write && !read) !MobileGuestRoutes.acceptable(request)
      else
        (write || MobileReadRoutes.bare(request.method, request.path) ||
        MobileReviewRoutes.mark(request.method, request.path)) &&
        (request.rawQueryString.nonEmpty || request.hasBody)
    if (badShape) return denied(400, "invalid_request")
    oauth.authenticate(parts(1)).flatMap { grant =>
      // MobileOAuthService.authenticate has already checked the scopes against the client.
      grant.flatMap(value => MobileScopes.parse(value.scope).map(value -> _)) match {
        case Some((value, scopes)) =>
          // Admin grants reach only admin routes, organizer grants only organizer routes; app
          // grants reach neither.
          val adminGrant    = MobileScopes.isAdmin(scopes)
          val organizeGrant = MobileScopes.isOrganize(scopes)
          val staffGrant    = adminGrant || organizeGrant
          if (if (adminGrant) !admin else if (organizeGrant) !organize else !write && !read)
            denied(403, "insufficient_scope")
          else if (!adminGrant && settings.writeControlEnabled &&
                   MobileGuestRoutes.guestOnlyWhenDisabled(request.method, request.path) &&
                   !writePolicy.enabled) denied(403, "mobile_writes_disabled")
          else if (write && !scopes.contains(MobileScopes.Write) ||
                   editor && !scopes.contains(MobileScopes.Edit)) denied(403, "insufficient_scope")
          // D13 (1): the claim page's grant writes only through the claim routes.
          else if (value.clientId == MobileClaimRoutes.ClientId && (write && !claimRoute ||
                   MobileGuestRoutes.guestOnlyWhenDisabled(request.method, request.path)))
            denied(403, "insufficient_scope")
          // While field writes are off only super-users' admin grants write (fork-changes.md);
          // organizers' writes wait like everyone else's.
          else if (organizeGrant && request.method != "GET" && settings.writeControlEnabled &&
                   !writePolicy.enabled) denied(403, "mobile_writes_disabled")
          // D13: with the switch off, only the claim page's grant may claim.
          else if (switchedOff && value.clientId != MobileClaimRoutes.ClientId)
            denied(403, "mobile_writes_disabled")
          else
            users.retrieve(value.userId).filter(_.id > 0) match {
              // Re-checked on every request, so a demotion takes effect at once.
              case Some(user) if adminGrant && !admins.isAdmin(user) =>
                denied(403, "admin_required")
              case Some(user) if organizeGrant && !admins.isOrganizer(user.id) =>
                denied(403, "organizer_required")
              case Some(user) =>
                val gate =
                  if (staffGrant) Future.successful(None)
                  else
                    draftGate(Some(user), write, request).flatMap {
                      case None => choiceGate(request)
                      case some => Future.successful(some)
                    }
                gate.flatMap {
                  case Some(refusal) => Future.successful(refusal)
                  case None =>
                    val result = next(
                      request
                        .addAttr(MobileBearerIdentity.UserKey, user)
                        .addAttr(MobileBearerIdentity.ScopesKey, scopes)
                        .addAttr(MobileBearerIdentity.FamilyKey, value.familyId)
                        .addAttr(MobileBearerIdentity.ClientKey, value.clientId)
                    )
                    if (staffGrant && MobileAdminRoutes.stockWrite(request.method, request.path))
                      result.transformWith(outcome => auditStockWrite(user, request, outcome))
                    else if (switchedOff)
                      result
                        .transformWith(outcome => audit(user, "claim.writes_off", request, outcome))
                    else result
                }
              case None => denied(401, "invalid_token")
            }
        // Not an app grant: maybe a guest access token. Guests reach only MobileGuestRoutes.
        case None if guests.enabled =>
          guests.authenticate(parts(1)).flatMap {
            case Some(value) if guest =>
              draftGate(None, write = false, request).flatMap {
                case Some(refusal) => Future.successful(refusal)
                case None          => next(request.addAttr(MobileBearerIdentity.GuestKey, value))
              }
            case Some(_) => denied(403, "insufficient_scope")
            case None    => denied(401, "invalid_token")
          }
        case None => denied(401, "invalid_token")
      }
    }
  }
}
