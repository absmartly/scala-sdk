package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite

class AssignerTest extends AnyFunSuite {

  test("chooseVariant returns correct variant for boundary conditions") {
    assert(Utils.chooseVariant(List(0.0, 1.0), 0.0) == 1)
    assert(Utils.chooseVariant(List(0.0, 1.0), 0.5) == 1)
    assert(Utils.chooseVariant(List(1.0, 0.0), 0.0) == 0)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.0) == 0)
    assert(Utils.chooseVariant(List(0.5, 0.5), 0.5) == 1)
    assert(Utils.chooseVariant(List(0.33, 0.33, 0.34), 0.0) == 0)
    assert(Utils.chooseVariant(List(0.33, 0.33, 0.34), 0.33) == 1)
    assert(Utils.chooseVariant(List(0.33, 0.33, 0.34), 0.66) == 2)
  }

  val canonicalTestCases: List[(String, List[Double], Int, Int, Int)] = List(
    ("bleh@absmartly.com", List(0.5, 0.5), 0x00000000, 0x00000000, 0),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x00000000, 0x00000001, 1),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x8015406f, 0x7ef49b98, 0),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x3b2e7d90, 0xca87df4d, 0),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x52c1f657, 0xd248bb2e, 0),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x865a84d0, 0xaa22d41a, 0),
    ("bleh@absmartly.com", List(0.5, 0.5), 0x27d1dc86, 0x845461b9, 1),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x00000000, 0x00000000, 0),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x00000000, 0x00000001, 2),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x8015406f, 0x7ef49b98, 0),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x3b2e7d90, 0xca87df4d, 0),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x52c1f657, 0xd248bb2e, 0),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x865a84d0, 0xaa22d41a, 1),
    ("bleh@absmartly.com", List(0.33, 0.33, 0.34), 0x27d1dc86, 0x845461b9, 1),
    ("123456789", List(0.5, 0.5), 0x00000000, 0x00000000, 1),
    ("123456789", List(0.5, 0.5), 0x00000000, 0x00000001, 0),
    ("123456789", List(0.5, 0.5), 0x8015406f, 0x7ef49b98, 1),
    ("123456789", List(0.5, 0.5), 0x3b2e7d90, 0xca87df4d, 1),
    ("123456789", List(0.5, 0.5), 0x52c1f657, 0xd248bb2e, 1),
    ("123456789", List(0.5, 0.5), 0x865a84d0, 0xaa22d41a, 0),
    ("123456789", List(0.5, 0.5), 0x27d1dc86, 0x845461b9, 0),
    ("123456789", List(0.33, 0.33, 0.34), 0x00000000, 0x00000000, 2),
    ("123456789", List(0.33, 0.33, 0.34), 0x00000000, 0x00000001, 1),
    ("123456789", List(0.33, 0.33, 0.34), 0x8015406f, 0x7ef49b98, 2),
    ("123456789", List(0.33, 0.33, 0.34), 0x3b2e7d90, 0xca87df4d, 2),
    ("123456789", List(0.33, 0.33, 0.34), 0x52c1f657, 0xd248bb2e, 2),
    ("123456789", List(0.33, 0.33, 0.34), 0x865a84d0, 0xaa22d41a, 0),
    ("123456789", List(0.33, 0.33, 0.34), 0x27d1dc86, 0x845461b9, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x00000000, 0x00000000, 1),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x00000000, 0x00000001, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x8015406f, 0x7ef49b98, 1),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x3b2e7d90, 0xca87df4d, 1),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x52c1f657, 0xd248bb2e, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x865a84d0, 0xaa22d41a, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.5, 0.5), 0x27d1dc86, 0x845461b9, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x00000000, 0x00000000, 2),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x00000000, 0x00000001, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x8015406f, 0x7ef49b98, 2),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x3b2e7d90, 0xca87df4d, 1),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x52c1f657, 0xd248bb2e, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x865a84d0, 0xaa22d41a, 0),
    ("e791e240fcd3df7d238cfc285f475e8152fcc0ec", List(0.33, 0.33, 0.34), 0x27d1dc86, 0x845461b9, 1)
  )

  canonicalTestCases.zipWithIndex.foreach { case ((unit, split, seedHi, seedLo, expected), idx) =>
    test(s"assign canonical case $idx: unit='${unit.take(20)}' split=${split.mkString(",")} seeds=0x${Integer.toHexString(seedHi)}:0x${Integer.toHexString(seedLo)}") {
      val hashedUnit = Utils.hashUnit(unit)
      val assigner = new VariantAssigner(hashedUnit)
      val variant = assigner.assign(split, seedHi, seedLo)
      assert(variant == expected,
        s"Expected variant $expected, got $variant for unit='$unit', split=$split, seedHi=0x${Integer.toHexString(seedHi)}, seedLo=0x${Integer.toHexString(seedLo)}")
    }
  }
}
