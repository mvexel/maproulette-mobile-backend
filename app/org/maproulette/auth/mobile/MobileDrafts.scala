package org.maproulette.auth.mobile

import anorm._
import javax.inject.{Inject, Singleton}
import play.api.db.Database

/**
  * Preview mode (design/preview-mode.md §6): a challenge that is disabled, or whose project is
  * disabled, is a draft. App grants read a draft only when the user may organize it; nobody writes
  * to one.
  */
object MobileDraftRoutes {
  private val challenge = "/api/v2/challenge/([0-9]+)(?:/tags|/tasks)?".r
  private val task      = "/api/v2/task/([0-9]+)(?:/choice/check|/tags)?".r
  // Release stays open so a lock taken before the challenge was disabled can still be dropped.
  private val taskWrite = "/api/v2/task/([0-9]+)/(?:start|skip|choice|[1256])".r

  sealed trait Target
  case class Challenge(id: Long) extends Target
  case class Task(id: Long)      extends Target

  /** The challenge or task a mobile read or write route addresses, if any. */
  def target(method: String, path: String): Option[Target] = (method, path) match {
    case ("GET", challenge(id))          => Some(Challenge(id.toLong))
    case ("GET", task(id))               => Some(Task(id.toLong))
    case ("GET", taskWrite(id))          => Some(Task(id.toLong))
    case ("POST" | "PUT", taskWrite(id)) => Some(Task(id.toLong))
    case _                               => None
  }
}

@com.google.inject.ImplementedBy(classOf[MobileDraftLookup])
trait MobileDrafts {

  /** True when the challenge (or the task's challenge) is a draft; false when published or unknown. */
  def draft(target: MobileDraftRoutes.Target): Boolean
}

object MobileDrafts {

  /** Everything is published (unit tests of other filter behaviour). */
  val NoDrafts: MobileDrafts = new MobileDrafts {
    def draft(target: MobileDraftRoutes.Target): Boolean = false
  }
}

@Singleton
class MobileDraftLookup @Inject() (db: Database) extends MobileDrafts {
  // Unknown ids are not drafts: the route answers 404 for them on its own.
  override def draft(target: MobileDraftRoutes.Target): Boolean = db.withConnection { implicit c =>
    val visible = target match {
      case MobileDraftRoutes.Challenge(id) =>
        SQL"""SELECT c.enabled AND p.enabled AS visible FROM challenges c
                JOIN projects p ON p.id = c.parent_id WHERE c.id = $id"""
      case MobileDraftRoutes.Task(id) =>
        SQL"""SELECT c.enabled AND p.enabled AS visible FROM tasks t
                JOIN challenges c ON c.id = t.parent_id
                JOIN projects p ON p.id = c.parent_id WHERE t.id = $id"""
    }
    visible.as(SqlParser.bool("visible").singleOpt).contains(false)
  }
}
