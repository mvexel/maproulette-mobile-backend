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
  private val taskWrite =
    "/api/v2/task/([0-9]+)/(?:start|skip|choice|[1256]|comment|refreshLock|changeset|lockBundle)".r
  // Delete, unbundle and bundle unlock stay open for the same reason as release.
  private val bundle = "/api/v2/taskBundle/([0-9]+)(?:/update|/[1256]|/comment)?".r
  private val withMembers =
    "/api/v2/(?:task/[0-9]+/lockBundle|taskBundle/[0-9]+/update)".r

  sealed trait Target
  case class Challenge(id: Long) extends Target
  case class Task(id: Long)      extends Target
  // A bundle is a draft when any of its tasks is.
  case class Bundle(id: Long) extends Target

  /** The challenge or task a mobile read or write route addresses, if any. */
  def target(method: String, path: String): Option[Target] = (method, path) match {
    case ("GET", challenge(id))          => Some(Challenge(id.toLong))
    case ("GET", task(id))               => Some(Task(id.toLong))
    case ("GET", taskWrite(id))          => Some(Task(id.toLong))
    case ("POST" | "PUT", taskWrite(id)) => Some(Task(id.toLong))
    case ("POST" | "PUT", bundle(id))    => Some(Bundle(id.toLong))
    case _                               => None
  }

  /** The member tasks a bundle lock or update would add (its `taskIds`, digits checked upstream). */
  def members(method: String, path: String, query: Map[String, Seq[String]]): Seq[Long] =
    (method, path) match {
      case ("POST" | "PUT", withMembers()) =>
        query.getOrElse("taskIds", Nil).filter(_.matches("[0-9]{1,18}")).map(_.toLong)
      case _ => Nil
    }
}

@com.google.inject.ImplementedBy(classOf[MobileDraftLookup])
trait MobileDrafts {

  /** True when the challenge (or the task's challenge) is a draft; false when published or unknown. */
  def draft(target: MobileDraftRoutes.Target): Boolean

  /**
    * True when the task is a multiple-choice task (`cooperativeWork.meta.type = 3`); false when it
    * is not or is unknown. App grants change its status only through `POST /task/:id/choice`.
    */
  def choiceTask(taskId: Long): Boolean

  /** True when any of these tasks is a draft. */
  def draftTasks(taskIds: Seq[Long]): Boolean =
    taskIds.exists(id => draft(MobileDraftRoutes.Task(id)))

  /** True when the bundle holds a multiple-choice task; false when not or unknown. */
  def choiceBundle(bundleId: Long): Boolean
}

object MobileDrafts {

  /** Everything is published (unit tests of other filter behaviour). */
  val NoDrafts: MobileDrafts = new MobileDrafts {
    def draft(target: MobileDraftRoutes.Target): Boolean = false
    def choiceTask(taskId: Long): Boolean                = false
    def choiceBundle(bundleId: Long): Boolean            = false
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
      case MobileDraftRoutes.Bundle(id) =>
        SQL"""SELECT bool_and(c.enabled AND p.enabled) AS visible FROM task_bundles tb
                JOIN tasks t ON t.id = tb.task_id
                JOIN challenges c ON c.id = t.parent_id
                JOIN projects p ON p.id = c.parent_id WHERE tb.bundle_id = $id"""
    }
    visible.as(SqlParser.get[Option[Boolean]]("visible").singleOpt).flatten.contains(false)
  }

  override def draftTasks(taskIds: Seq[Long]): Boolean =
    taskIds.nonEmpty && db.withConnection { implicit c =>
      SQL"""SELECT 1 FROM tasks t
              JOIN challenges c ON c.id = t.parent_id
              JOIN projects p ON p.id = c.parent_id
              WHERE t.id IN ($taskIds) AND NOT (c.enabled AND p.enabled) LIMIT 1"""
        .as(SqlParser.scalar[Int].singleOpt)
        .isDefined
    }

  override def choiceBundle(bundleId: Long): Boolean = db.withConnection { implicit c =>
    SQL"""SELECT 1 FROM task_bundles tb JOIN tasks t ON t.id = tb.task_id
            WHERE tb.bundle_id = $bundleId AND t.cooperative_work_json->'meta'->>'type' = '3'
            LIMIT 1"""
      .as(SqlParser.scalar[Int].singleOpt)
      .isDefined
  }

  override def choiceTask(taskId: Long): Boolean = db.withConnection { implicit c =>
    SQL"""SELECT 1 FROM tasks
            WHERE id = $taskId AND cooperative_work_json->'meta'->>'type' = '3'"""
      .as(SqlParser.scalar[Int].singleOpt)
      .isDefined
  }
}
