/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import anorm.NamedParameter
import org.maproulette.framework.psql.filter.SQLClause

case class OrderField(
    name: String,
    direction: Int = Order.DESC,
    table: Option[String] = None,
    isColumn: Boolean = true
)

/**
  * Basic class to handle any ordering that needs to be added to the query
  *
  * @author mcuthbert
  */
case class Order(fields: List[OrderField] = List.empty) extends SQLClause {
  override def sql()(implicit table: String = ""): String = {
    val directions = fields.map(field => field.direction).distinct
    val orderFields = fields
      .map(field => {
        if (field.isColumn) {
          SQLUtils.testColumnName(field.name)
        }
        val fieldDirection = if (directions.size > 1) Order.direction(field.direction) else ""
        val columnKey = field.table.getOrElse(table) match {
          case ""    => field.name
          case value => s"$value.${field.name}"
        }
        s"$columnKey $fieldDirection".trim
      })
      .mkString(",")
      .trim

    if (fields.nonEmpty) {
      val orderDirection = if (directions.size == 1) Order.direction(directions.head) else ""
      s"ORDER BY $orderFields $orderDirection".trim
    } else {
      ""
    }
  }

  override def parameters(): List[NamedParameter] = List.empty
}

object Order {
  val ASC: Int  = -1
  val DESC: Int = 1

  def >(field: String, direction: Int = Order.DESC): Order =
    Order(List(OrderField(field, direction)))

  def direction(value: Int): String = value match {
    case 1 => "DESC"
    case _ => "ASC"
  }
}
