/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */
package org.maproulette.framework.model

import sangria.execution.deferred.HasId

/**
  * Represents models that have an id, defining an implicit `hasId` needed for
  * graphQL queries
  *
  * @author nrotstan
  */
trait Identifiable {
  val id: Long
}
object Identifiable {
  implicit def hasId[T <: Identifiable]: HasId[T, Long] = HasId(_.id)
}
