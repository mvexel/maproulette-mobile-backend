/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import anorm.NamedParameter
import org.maproulette.framework.psql.filter.SQLClause
import org.maproulette.utils.Utils

/**
  * Basic class that handles all the paging for a query
  *
  * @author mcuthbert
  */
case class Paging(limit: Int = 0, page: Int = 0) extends SQLClause {
  val randomPrefix: String = Utils.randomStringFromCharList(5)

  override def sql()(implicit table: String = ""): String = {
    if (limit > 0) {
      s"LIMIT {${randomPrefix}limit} OFFSET {${randomPrefix}offset}"
    } else {
      ""
    }
  }

  override def parameters(): List[NamedParameter] = {
    if (limit > 0) {
      List(
        Symbol(s"${randomPrefix}limit")  -> limit,
        Symbol(s"${randomPrefix}offset") -> page * limit
      )
    } else {
      List.empty
    }
  }
}
