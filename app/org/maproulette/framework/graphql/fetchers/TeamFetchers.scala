/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.fetchers

import org.maproulette.framework.model.{TeamUser}
import org.maproulette.framework.graphql.UserContext
import sangria.execution.deferred.{Fetcher, Relation, RelationIds}
import scala.concurrent.{Future}

/**
  * @author nrotstan
  */
trait TeamFetchers {}
object TeamFetchers {
  val teamsFetcher = Fetcher((ctx: UserContext, ids: Seq[Long]) =>
    Future.successful(
      ctx.services.team.list(ids.toList, ctx.user).toSeq
    )
  )

  val teamUsersByTeamRel = Relation[TeamUser, Long]("onTeam", u => Seq(u.teamId))
  val teamUsersFetcher = Fetcher.rel(
    (ctx: UserContext, ids: Seq[Long]) =>
      Future.successful(ctx.services.team.listTeamUsers(ids.toList, ctx.user).toSeq),
    (ctx: UserContext, ids: RelationIds[TeamUser]) =>
      Future.successful(
        ctx.services.team.teamUsersByTeamIds(ids(teamUsersByTeamRel).toList, ctx.user).toSeq
      )
  )

  val teamUsersByUserRel = Relation[TeamUser, Long]("withTeam", u => Seq(u.userId))
  val userTeamsFetcher = Fetcher.rel(
    (ctx: UserContext, ids: Seq[Long]) =>
      Future.successful(ctx.services.team.listTeamUsers(ids.toList, ctx.user).toSeq),
    (ctx: UserContext, ids: RelationIds[TeamUser]) =>
      Future.successful(
        ctx.services.team.teamUsersByUserIds(ids(teamUsersByUserRel).toList, ctx.user).toSeq
      )
  )

  val fetchers = List(teamsFetcher, teamUsersFetcher, userTeamsFetcher)
}
