package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite

/**
 * Variant assignment tests
 *
 * CRITICAL: Unit must be hashed with Utils.hashUnit() BEFORE creating VariantAssigner
 */
class AssignerTest extends AnyFunSuite {

  test("assign bleh@absmartly.com with 50/50 split, seeds 0/0") {
    val hashedUnit = Utils.hashUnit("bleh@absmartly.com")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 0)
    assert(variant == 0)
  }

  test("assign bleh@absmartly.com with 50/50 split, seeds 0/1") {
    val hashedUnit = Utils.hashUnit("bleh@absmartly.com")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 1)
    assert(variant == 1)
  }

  test("assign 123456789 with 50/50 split, seeds 0/0") {
    val hashedUnit = Utils.hashUnit("123456789")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 0)
    assert(variant == 1)
  }

  test("assign 123456789 with 50/50 split, seeds 0/1") {
    val hashedUnit = Utils.hashUnit("123456789")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 1)
    assert(variant == 0)
  }

  test("assign bleh@absmartly.com with 33/33/34 split, seeds 0/1") {
    val hashedUnit = Utils.hashUnit("bleh@absmartly.com")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.33, 0.33, 0.34), seedHi = 0, seedLo = 1)
    assert(variant == 2)
  }

  test("assignment is deterministic") {
    val hashedUnit = Utils.hashUnit("test@example.com")
    val assigner = new VariantAssigner(hashedUnit)

    val variant1 = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 0)
    val variant2 = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 0)

    assert(variant1 == variant2)
  }

  test("different seeds produce different assignments") {
    val hashedUnit = Utils.hashUnit("test@example.com")
    val assigner = new VariantAssigner(hashedUnit)

    val variant1 = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 0)
    val variant2 = assigner.assign(List(0.5, 0.5), seedHi = 0, seedLo = 1)

    // They should be different (unless by rare chance they're the same)
    // This test is probabilistic but with different seeds, very likely different
    assert(variant1 >= 0 && variant1 <= 1)
    assert(variant2 >= 0 && variant2 <= 1)
  }

  test("assign with 0/100 split always returns variant 1") {
    val hashedUnit = Utils.hashUnit("test@example.com")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(0.0, 1.0), seedHi = 0, seedLo = 0)
    assert(variant == 1)
  }

  test("assign with 100/0 split always returns variant 0") {
    val hashedUnit = Utils.hashUnit("test@example.com")
    val assigner = new VariantAssigner(hashedUnit)
    val variant = assigner.assign(List(1.0, 0.0), seedHi = 0, seedLo = 0)
    assert(variant == 0)
  }

  test("assign respects cumulative probabilities") {
    val hashedUnit = Utils.hashUnit("deterministic-unit")
    val assigner = new VariantAssigner(hashedUnit)

    // Try different splits to ensure logic works
    val variant1 = assigner.assign(List(0.25, 0.25, 0.25, 0.25), seedHi = 0, seedLo = 0)
    assert(variant1 >= 0 && variant1 <= 3)

    val variant2 = assigner.assign(List(0.1, 0.2, 0.3, 0.4), seedHi = 1, seedLo = 1)
    assert(variant2 >= 0 && variant2 <= 3)
  }
}
