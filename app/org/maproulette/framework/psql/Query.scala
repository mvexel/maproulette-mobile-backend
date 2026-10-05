/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import anorm._
import org.maproulette.framework.psql.filter.{Filter, FilterGroup, Parameter, SQLClause}
import org.slf4j.{Logger, LoggerFactory}

/**
  * Class that handles a basic PsqlQuery options that can be used to modify the base queries in the
  * Repository. This allows a user to make minor modifications without having to actually write SQL
  * queries.
  *
  * @author mcuthbert
  */
object Query {
  val logger: Logger = LoggerFactory.getLogger(Query.getClass)

  //val config:Config
  def devMode(): Boolean = true //config.isDebugMode || config.isDevMode

  def simple(
      parameters: List[Parameter[_]],
      base: String = "",
      key: SQLKey = AND(),
      paging: Paging = Paging(),
      order: Order = Order(),
      grouping: Grouping = Grouping(),
      includeWhere: Boolean = true,
      appendBase: Boolean = false,
      finalClause: String = ""
  ): Query =
    Query(
      Filter(List(FilterGroup(parameters, key)), key),
      base,
      paging,
      order,
      grouping,
      includeWhere,
      appendBase,
      finalClause
    )

  def empty = Query.simple(List())
}

case class Query(
    filter: Filter,
    base: String = "",
    paging: Paging = Paging(),
    order: Order = Order(),
    grouping: Grouping = Grouping(),
    includeWhere: Boolean = true,
    appendBase: Boolean = false,
    finalClause: String = ""
) extends SQLClause {
  def build(
      baseQuery: String = "",
      defaultGrouping: Grouping = Grouping()
  )(implicit baseTable: String = ""): SimpleSql[Row] = {
    val parameters = this.parameters()
    val sql        = this.sqlWithBaseQuery(baseQuery, appendBase, defaultGrouping)
    if (parameters.nonEmpty) {
      SQL(sql).on(parameters: _*)
    } else {
      SQL(sql).asSimple[Row]()
    }
  }

  def addFilterGroup(group: FilterGroup) = {
    Query(
      Filter(group :: filter.groups),
      base,
      paging,
      order,
      grouping,
      includeWhere
    )
  }

  override def parameters(): List[NamedParameter] = filter.parameters() ++ paging.parameters()

  override def sql()(implicit table: String = ""): String = this.sqlWithBaseQuery()

  def sqlWithBaseQuery(
      baseQuery: String = "",
      appendBase: Boolean = false,
      defaultGrouping: Grouping = Grouping()
  )(implicit baseTable: String = ""): String = {
    val filterQuery = filter.sql() match {
      case x if x.nonEmpty & includeWhere => s"WHERE $x"
      case x                              => x
    }
    val pagingQuery = paging.sql()
    val start = if (appendBase) {
      s"$baseQuery ${this.base}"
    } else {
      this.base match {
        case "" => baseQuery
        case _  => this.base
      }
    }

    val sqlGrouping = grouping.sql() match {
      case "" => defaultGrouping.sql()
      case _  => grouping.sql()
    }

    val query =
      s"$start${this.format(filterQuery)}${this.format(sqlGrouping)}${this.format(order.sql())}${this
        .format(pagingQuery)}${this.format(this.finalClause)}"
    if (Query.devMode()) {
      Query.logger.debug(query)
    }
    query
  }
}

trait Base extends SQLClause
