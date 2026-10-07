package org.maproulette.auth.mobile

import anorm._
import javax.inject.{Inject, Singleton}
import play.api.db.Database
import play.api.libs.json.Json

/** Database-backed, per-deployment task-write switch. Read on every write for immediate effect. */
@Singleton
class MobileWritePolicy @Inject() (db: Database) {
  def enabled: Boolean = db.withConnection { implicit c =>
    SQL("SELECT enabled FROM mobile_write_policy WHERE id = 1")
      .as(SqlParser.bool("enabled").single)
  }

  /** The policy update and audit record commit together. */
  def set(enabled: Boolean, actor: Long): Boolean = db.withTransaction { implicit c =>
    val before = SQL("SELECT enabled FROM mobile_write_policy WHERE id = 1 FOR UPDATE")
      .as(SqlParser.bool("enabled").single)
    if (before != enabled) {
      SQL(
        "UPDATE mobile_write_policy SET enabled = {enabled}, updated_by = {actor}, updated_at = NOW() WHERE id = 1"
      ).on("enabled" -> enabled, "actor" -> actor)
        .executeUpdate()
      SQL(
        """INSERT INTO mobile_admin_audit(actor_user_id,action,target,before,after)
        VALUES ({actor},'write_policy.update','mobile',CAST({before} AS jsonb),CAST({after} AS jsonb))"""
      ).on(
          "actor"  -> actor,
          "before" -> Json.stringify(Json.obj("enabled" -> before)),
          "after"  -> Json.stringify(Json.obj("enabled" -> enabled))
        )
        .executeUpdate()
    }
    enabled
  }
}
