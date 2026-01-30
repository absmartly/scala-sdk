package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite

/**
 * Murmur3_32 hash tests - matching JavaScript SDK test suite
 *
 * CRITICAL: These test vectors MUST pass for correct variant assignment
 */
class Murmur3Test extends AnyFunSuite {

  // Test cases from JavaScript SDK
  val testCases = List(
    ("", 0x00000000, 0x00000000),
    (" ", 0x00000000, 0x7ef49b98),
    ("t", 0x00000000, 0xca87df4d),
    ("te", 0x00000000, 0xedb8ee1b),
    ("tes", 0x00000000, 0x0bb90e5a),
    ("test", 0x00000000, 0xba6bd213),
    ("testy", 0x00000000, 0x44af8342),
    ("testy1", 0x00000000, 0x8a1a243a),
    ("testy12", 0x00000000, 0x845461b9),
    ("testy123", 0x00000000, 0x47628ac4),
    ("The quick brown fox jumps over the lazy dog", 0x00000000, 0x2e4ff723),
    ("", 0xdeadbeef, 0x0de5c6a9),
    (" ", 0xdeadbeef, 0x25acce43),
    ("t", 0xdeadbeef, 0x3b15dcf8),
    ("te", 0xdeadbeef, 0xac981332),
    ("tes", 0xdeadbeef, 0xc1c78dda),
    ("test", 0xdeadbeef, 0xaa22d41a),
    ("testy", 0xdeadbeef, 0x84f5f623),
    ("testy1", 0xdeadbeef, 0x09ed28e9),
    ("testy12", 0xdeadbeef, 0x22467835),
    ("testy123", 0xdeadbeef, 0xd633060d),
    ("The quick brown fox jumps over the lazy dog", 0xdeadbeef, 0x3a7b3f4d),
    ("", 0x00000001, 0x514e28b7),
    (" ", 0x00000001, 0x4f0f7132),
    ("t", 0x00000001, 0x5db1831e),
    ("te", 0x00000001, 0xd248bb2e),
    ("tes", 0x00000001, 0xd432eb74),
    ("test", 0x00000001, 0x99c02ae2),
    ("testy", 0x00000001, 0xc5b2dc1e),
    ("testy1", 0x00000001, 0x33925ceb),
    ("testy12", 0x00000001, 0xd92c9f23),
    ("testy123", 0x00000001, 0x3bc1712d),
    ("The quick brown fox jumps over the lazy dog", 0x00000001, 0x78e69e27)
  )

  testCases.zipWithIndex.foreach { case ((input, seed, expected), idx) =>
    test(s"hash test case $idx: '$input' with seed 0x${seed.toHexString}") {
      val result = Murmur3.hashString(input, seed)
      assert(result == expected, s"Expected 0x${expected.toHexString}, got 0x${result.toHexString}")
    }
  }
}

