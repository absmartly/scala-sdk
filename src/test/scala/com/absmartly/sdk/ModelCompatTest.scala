package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite
import io.circe.syntax._

/**
 * Model calls written exactly as against 0.2.0 (before `ruleOverride` and
 * `assignmentRules`): the old-arity constructors and `apply`s still exist and
 * give the new fields their neutral values.
 */
class ModelCompatTest extends AnyFunSuite {

  test("the 11-argument Exposure apply and constructor default ruleOverride to false") {
    val applied = Exposure(1, "exp", "session_id", 1, 123L, true, true, false, false, false, false)
    val constructed = new Exposure(1, "exp", "session_id", 1, 123L, true, true, false, false, false, false)
    assert(!applied.ruleOverride && !constructed.ruleOverride)
    assert(applied == constructed)
    assert(applied.asJson.hcursor.get[Boolean]("ruleOverride").contains(false))
  }

  test("the 16-argument ExperimentData apply and constructor have no assignment rules") {
    val applied = ExperimentData(1, "exp", "session_id", 1, 0, 0, List(0.5, 0.5), 0, 0, List(0.0, 1.0), 0, Nil, Nil, false, None, None)
    val constructed =
      new ExperimentData(1, "exp", "session_id", 1, 0, 0, List(0.5, 0.5), 0, 0, List(0.0, 1.0), 0, Nil, Nil, false, None, None)
    assert(applied.assignmentRules.isEmpty && constructed.assignmentRules.isEmpty)
    assert(applied == constructed)
  }
}
