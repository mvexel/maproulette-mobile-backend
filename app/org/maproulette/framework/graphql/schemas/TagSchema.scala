/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.graphql.schemas

import javax.inject.Inject
import org.maproulette.exception.NotFoundException
import org.maproulette.framework.graphql.UserContext
import org.maproulette.framework.model.Tag
import org.maproulette.framework.psql.{Order, OrderField, Paging}
import org.maproulette.framework.service.TagService
import play.api.libs.json.Json
import sangria.macros.derive._
import sangria.schema._

/**
  * @author mcuthbert
  */
case class ChallengeMapItem(id: Long, tags: List[Tag])

class TagSchema @Inject() (override val service: TagService)
    extends MRSchema[Tag]
    with MRSchemaTypes {
  implicit val ChallengeMapItemType: ObjectType[Unit, ChallengeMapItem] =
    deriveObjectType[Unit, ChallengeMapItem](ObjectTypeName("ChallengeMapItem"))

  val queries: List[Field[UserContext, Unit]] = List(
    Field(
      name = "keyword",
      description = Some("Retrieve a keyword based on the provided identifier"),
      fieldType = OptionType(TagType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.retrieve(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "keywords",
      description = Some("List all the tags based on the search criteria"),
      fieldType = ListType(TagType),
      arguments = TagSchema.searchStringArg :: TagSchema.tagTypeArg :: MRSchema.pagingLimitArg :: MRSchema.pagingOffsetArg :: MRSchema.orderArg :: TagSchema.usePrefixArg :: Nil,
      resolve = context =>
        this.service.find(
          context.arg(TagSchema.searchStringArg),
          List(context.arg(TagSchema.tagTypeArg)),
          Paging(context.arg(MRSchema.pagingLimitArg), context.arg(MRSchema.pagingOffsetArg)),
          Order(context.arg(MRSchema.orderArg).getOrElse(Seq.empty).toList.map(OrderField(_))),
          context.arg(TagSchema.usePrefixArg)
        )
    ),
    Field(
      name = "keywordByName",
      description = Some("Retrieve a keyword based on it's name"),
      fieldType = OptionType(TagType),
      arguments = MRSchema.nameArg :: TagSchema.parentArg :: Nil,
      resolve = context =>
        this.service.retrieveByName(
          context.arg(MRSchema.nameArg),
          context.arg(TagSchema.tagTypeArg),
          context.arg(TagSchema.parentArg)
        )
    ),
    Field(
      name = "keywordsByTask",
      description = Some("Retrieves all the keywords for a given task"),
      fieldType = ListType(TagType),
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.listByTask(context.arg(MRSchema.idArg))
    ),
    Field(
      name = "keywordsByChallenges",
      description = Some("Retrieve all the keywords for a given challenge"),
      fieldType = ListType(ChallengeMapItemType),
      arguments = MRSchema.idsArg :: Nil,
      resolve = context =>
        this.service
          .listByChallenges(context.arg(MRSchema.idsArg).toList)
          .map { case (k, v) => ChallengeMapItem(k, v) }
          .toList
    ),
    Field(
      name = "keywordsByName",
      description = Some("Retrieve all the keywords with the names in the provided list"),
      fieldType = ListType(TagType),
      arguments = TagSchema.namesArg :: TagSchema.parentArg :: Nil,
      resolve = context =>
        this.service.retrieveListByName(
          context.arg(TagSchema.namesArg).toList,
          context.arg(TagSchema.parentArg)
        )
    )
  )

  val mutations: List[Field[UserContext, Unit]] = List(
    Field(
      name = "createKeyword",
      description = Some("Creates a Keyword"),
      fieldType = TagType,
      arguments = TagSchema.tagArg :: Nil,
      resolve = context =>
        this.service.create(context.arg(TagSchema.tagArg).copy(id = -1), context.ctx.user)
    ),
    Field(
      name = "updateKeyword",
      description = Some("Updates a Keyword"),
      fieldType = OptionType(TagType),
      arguments = MRSchema.idArg :: TagSchema.tagArg :: Nil,
      resolve = context => {
        val keywordId = context.arg(MRSchema.idArg)
        val keyword = this.service.retrieve(keywordId) match {
          case Some(k) => k
          case None =>
            throw new NotFoundException(s"No keyword with id $keywordId found to update!")
        }
        this.service
          .update(keyword.id, Json.toJson(context.arg(TagSchema.tagArg)), context.ctx.user)
      }
    ),
    Field(
      name = "deleteKeyword",
      description = Some("Deletes a Keyword"),
      fieldType = BooleanType,
      arguments = MRSchema.idArg :: Nil,
      resolve = context => this.service.delete(context.arg(MRSchema.idArg), context.ctx.user)
    )
  )
}

object TagSchema {
  import sangria.marshalling.playJson._

  implicit val TagInputType: InputObjectType[Tag] = deriveInputObjectType[Tag](
    InputObjectTypeName("KeywordInput"),
    InputObjectTypeDescription("A keyword for tasks and challenges in MapRoulette"),
    ExcludeInputFields("created", "modified")
  )

  val tagArg: Argument[Tag] = Argument("tag", TagInputType, "The tag object")
  val searchStringArg: Argument[String] =
    Argument("searchString", StringType, "A search string used to find keywords")
  val tagTypeArg: Argument[String] =
    Argument(
      "tagType",
      OptionInputType(StringType),
      "The type of keyword",
      defaultValue = "challenges"
    )
  val usePrefixArg: Argument[Boolean] = Argument(
    "usePrefix",
    OptionInputType(BooleanType),
    "Whether to set the search string as the prefix of the keyword, or just general search",
    defaultValue = false
  )
  val parentArg: Argument[Long] = Argument(
    "parentId",
    OptionInputType(LongType),
    "The parent identifier to limit the results, defaults to -1 which is all",
    defaultValue = -1
  )
  val namesArg = Argument("names", ListInputType(StringType), "A list of keyword names")
}
