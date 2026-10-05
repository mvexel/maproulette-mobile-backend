/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.model

import org.maproulette.framework.psql.CommonField

/**
  * @author mcuthbert
  */
object ChallengeSnapshot extends CommonField {
  val TABLE = "challenge_snapshots"

  val FIELD_CHALLENGE_ID = "challenge_id"
}
