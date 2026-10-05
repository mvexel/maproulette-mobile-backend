/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.mixins

import play.api.libs.json._

import org.maproulette.framework.service.ServiceManager
import org.maproulette.framework.model.{Challenge, Tag, Project, Task}

import org.maproulette.utils.Utils

/**
  * ParentMixin provides a method to insert parent data into the JSON
  */
trait ParentMixin {
  val serviceManager: ServiceManager

  /**
    * Fetches the matching parent project object and inserts it into the JSON data returned.
    *
    */
  def insertProjectJSON(challenges: List[Challenge]): JsValue = {
    // json writes for automatically writing Challenges to a json body response
    implicit val cWrites: Writes[Challenge] = Challenge.writes.challengeWrites

    if (challenges.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val tags = serviceManager.tag.listByChallenges(challenges.map(c => c.id))
      val projects = Some(
        serviceManager.project
          .list(challenges.map(c => c.general.parent))
          .map(p => p.id -> p)
          .toMap
      )

      var vpIds = scala.collection.mutable.Set[Long]()
      challenges.map(c => {
        c.general.virtualParents match {
          case Some(vps) =>
            vps.map(vp => vpIds += vp)
          case _ => // do nothing
        }
      })
      val vpObjects =
        serviceManager.project.list(vpIds.toList).map(p => p.id -> p).toMap

      val jsonList = challenges.map { c =>
        var updated = Utils.insertIntoJson(
          Json.toJson(c),
          Tag.TABLE,
          Json.toJson(tags.getOrElse(c.id, List.empty).map(_.name))
        )
        val projectJson = Json
          .toJson(projects.get(c.general.parent))
          .as[JsObject] - Project.KEY_GRANTS
        updated = Utils.insertIntoJson(updated, Challenge.KEY_PARENT, projectJson, true)

        c.general.virtualParents match {
          case Some(vps) =>
            val vpJson =
              Some(vps.map(vp => Json.toJson(vpObjects.get(vp)).as[JsObject] - Project.KEY_GRANTS))
            updated = Utils.insertIntoJson(updated, Challenge.KEY_VIRTUAL_PARENTS, vpJson, true)
          case _ => // do nothing
        }
        updated
      }
      Json.toJson(jsonList)
    }
  }

  /**
    * Fetches the matching parent object and inserts it (id, name, status)
    * into the JSON data returned. Also fetches and inserts usernames for
    * 'reviewRequestedBy' and 'reviewBy'
    */
  def insertChallengeJSON(
      tasks: List[Task],
      includeTags: Boolean = false
  ): JsValue = {
    if (tasks.isEmpty) {
      Json.toJson(List[JsValue]())
    } else {
      val fetchedChallenges = serviceManager.challenge.list(tasks.map(t => t.parent))

      val projects = Some(
        serviceManager.project
          .list(fetchedChallenges.map(c => c.general.parent))
          .map(p => p.id -> Json.obj("id" -> p.id, "name" -> p.name, "displayName" -> p.displayName)
          )
          .toMap
      )

      val challenges = Some(
        fetchedChallenges
          .map(c =>
            c.id ->
              Json.obj(
                "id"     -> c.id,
                "name"   -> c.name,
                "status" -> c.status,
                "parent" -> Json.toJson(projects.get(c.general.parent)).as[JsObject]
              )
          )
          .toMap
      )

      val mappers = Some(
        serviceManager.user
          .retrieveListById(tasks.map(t => t.review.reviewRequestedBy.getOrElse(0L)))
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val allReviewers = tasks.flatMap(t => {
        List(t.review.reviewedBy.map(r => r)).flatMap(r => r) ++
          t.review.additionalReviewers.getOrElse(List()) ++
          List(t.review.metaReviewedBy.map(r => r)).flatMap(r => r)
      })

      val reviewers = Some(
        serviceManager.user
          .retrieveListById(allReviewers.toList)
          .map(u => u.id -> Json.obj("username" -> u.name, "id" -> u.id))
          .toMap
      )

      val tagsMap: Map[Long, List[Tag]] = includeTags match {
        case true => serviceManager.tag.listByTasks(tasks.map(t => t.id))
        case _    => null
      }

      val jsonList = tasks.map { task =>
        val challengeJson = Json.toJson(challenges.get(task.parent)).as[JsObject]
        var updated =
          Utils.insertIntoJson(Json.toJson(task), Challenge.KEY_PARENT, challengeJson, true)
        if (task.review.reviewRequestedBy.getOrElse(0) != 0) {
          val mapperJson = Json.toJson(mappers.get(task.review.reviewRequestedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewRequestedBy", mapperJson, true)
        }
        if (task.review.reviewedBy.getOrElse(0) != 0) {
          val reviewerJson = Json.toJson(reviewers.get(task.review.reviewedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "reviewedBy", reviewerJson, true)
        }
        if (task.review.metaReviewedBy.getOrElse(0) != 0) {
          val metaReviewerJson =
            Json.toJson(reviewers.get(task.review.metaReviewedBy.get)).as[JsObject]
          updated = Utils.insertIntoJson(updated, "metaReviewedBy", metaReviewerJson, true)
        }
        if (task.review.additionalReviewers != None) {
          val additionalReviewerJson = Json
            .toJson(
              Map(
                "additionalReviewers" ->
                  task.review.additionalReviewers.getOrElse(List()).map(r => reviewers.get(r))
              )
            )
            .as[JsObject]

          updated = Utils.insertIntoJson(
            updated,
            "additionalReviewers",
            (additionalReviewerJson \ "additionalReviewers").get,
            true
          )
        }
        if (includeTags && tagsMap.contains(task.id)) {
          val tagsJson = Json.toJson(tagsMap(task.id))
          updated = Utils.insertIntoJson(updated, "tags", tagsJson, true)
        }
        updated
      }
      Json.toJson(jsonList)
    }
  }
}
