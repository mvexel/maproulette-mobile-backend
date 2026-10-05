/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.model.{Grant, Grantee, GrantTarget}
import org.maproulette.framework.service.GrantService
import sangria.schema._

/**
  * @author nrotstan
  */
class GrantSchema @Inject() (override val service: GrantService)
    extends MRSchema[Grant]
    with MRSchemaTypes {
  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "grant",
      description = Some("Retrieve a grant based on an ID"),
      fieldType = OptionType(GrantType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.retrieve(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "projectgrants",
      description = Some("Retrieve all the grants on a specific project"),
      fieldType = ListType(GrantType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context =>
        this.service
          .retrieveGrantsOn(GrantTarget.project(context.arg(MRSchema.idArg)), context.ctx.user)
    ),
    Field(
      name = "usergrants",
      description = Some("Retrieve all the grants to a specific user"),
      fieldType = ListType(GrantType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context =>
        this.service
          .retrieveGrantsTo(Grantee.user(context.arg(MRSchema.osmIdArg)), context.ctx.user)
    )
  )

  // No external mutations of Grants for now
  val mutations: List[Field[UserContext, Unit]] = List()
}

object GrantSchema {
  val roleArg = Argument(
    "role",
    IntType,
    "The granted role: -1 = SUPER USER, 1 = ADMIN, 2 = WRITE, 3 = READ"
  )
}
