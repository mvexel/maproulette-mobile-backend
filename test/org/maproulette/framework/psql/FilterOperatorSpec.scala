/*
 * Copyright (C) 2020 MapRoulette contributors (see CONTRIBUTORS.md).
 * Licensed under the Apache License, Version 2.0 (see LICENSE).
 */

package org.maproulette.framework.psql

import org.maproulette.exception.InvalidException
import org.maproulette.framework.psql.filter.Operator
import org.scalatestplus.play.PlaySpec

/**
  * @author mcuthbert
  */
class FilterOperatorSpec extends PlaySpec {
  private val KEY                   = "key"
  private implicit val parameterKey = "TestKey"

  "FilterOperator" should {
    "format EQ operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.EQ) mustEqual s"$KEY = {$parameterKey}"
    }

    "format NOT EQ operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.EQ, true) mustEqual s"NOT $KEY = {$parameterKey}"
    }

    "format NE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.NE) mustEqual s"$KEY <> {$parameterKey}"
    }

    "format NOT NE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.NE, true) mustEqual s"NOT $KEY <> {$parameterKey}"
    }

    "format GT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.GT) mustEqual s"$KEY > {$parameterKey}"
    }

    "format NOT GT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.GT, true) mustEqual s"NOT $KEY > {$parameterKey}"
    }

    "format GTE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.GTE) mustEqual s"$KEY >= {$parameterKey}"
    }

    "format NOT GTE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.GTE, true) mustEqual s"NOT $KEY >= {$parameterKey}"
    }

    "format LT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LT) mustEqual s"$KEY < {$parameterKey}"
    }

    "format NOT LT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LT, true) mustEqual s"NOT $KEY < {$parameterKey}"
    }

    "format LTE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LTE) mustEqual s"$KEY <= {$parameterKey}"
    }

    "format NOT LTE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LTE, negate = true) mustEqual s"NOT $KEY <= {$parameterKey}"
    }

    "format IN operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.IN) mustEqual s"$KEY IN ({$parameterKey})"
    }

    "format NOT IN operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.IN, negate = true) mustEqual s"NOT $KEY IN ({$parameterKey})"
    }

    "format LIKE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LIKE) mustEqual s"$KEY LIKE {$parameterKey}"
    }

    "format NOT LIKE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.LIKE, negate = true) mustEqual s"NOT $KEY LIKE {$parameterKey}"
    }

    "format ILIKE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.ILIKE) mustEqual s"$KEY ILIKE {$parameterKey}"
    }

    "format NOT ILIKE operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.ILIKE, negate = true) mustEqual s"NOT $KEY ILIKE {$parameterKey}"
    }

    "format SIMILAR TO operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.SIMILAR_TO) mustEqual s"$KEY SIMILAR TO {$parameterKey}"
    }

    "format NOT SIMILAR TO operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.SIMILAR_TO, negate = true) mustEqual s"NOT $KEY SIMILAR TO {$parameterKey}"
    }

    "format EXISTS operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.EXISTS) mustEqual s"EXISTS ({$parameterKey})"
    }

    "format NOT EXISTS operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.EXISTS, negate = true) mustEqual s"NOT EXISTS ({$parameterKey})"
    }

    "format BOOL operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.BOOL) mustEqual s"key"
    }

    "format AT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.AT) mustEqual s"$KEY && {$parameterKey}"
    }

    "format NOT AT operator correctly" in {
      Operator.format(KEY, parameterKey, Operator.AT, negate = true) mustEqual s"NOT $KEY && {$parameterKey}"
    }

    "format rightValue correctly if set" in {
      Operator.format(KEY, parameterKey, Operator.EQ, value = Some("test.key")) mustEqual s"$KEY = test.key"
    }

    "between should throw invalidException" in {
      intercept[InvalidException] {
        Operator.format(KEY, parameterKey, Operator.BETWEEN)
      }
    }
  }
}
