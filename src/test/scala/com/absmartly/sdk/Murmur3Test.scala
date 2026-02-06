package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite

class Murmur3Test extends AnyFunSuite {

  val testCases: List[(String, Int, Int)] = List(
    ("", 0x00000000, 0x00000000),
    (" ", 0x00000000, 0x7ef49b98),
    ("t", 0x00000000, 0xca87df4d.toInt),
    ("te", 0x00000000, 0xedb8ee1b.toInt),
    ("tes", 0x00000000, 0x0bb90e5a),
    ("test", 0x00000000, 0xba6bd213.toInt),
    ("testy", 0x00000000, 0x44af8342),
    ("testy1", 0x00000000, 0x8a1a243a.toInt),
    ("testy12", 0x00000000, 0x845461b9.toInt),
    ("testy123", 0x00000000, 0x47628ac4),
    ("special characters a\u00e7b\u2193c", 0x00000000, 0xbe83b140.toInt),
    ("The quick brown fox jumps over the lazy dog", 0x00000000, 0x2e4ff723),
    ("", 0xdeadbeef.toInt, 0x0de5c6a9),
    (" ", 0xdeadbeef.toInt, 0x25acce43),
    ("t", 0xdeadbeef.toInt, 0x3b15dcf8),
    ("te", 0xdeadbeef.toInt, 0xac981332.toInt),
    ("tes", 0xdeadbeef.toInt, 0xc1c78dda.toInt),
    ("test", 0xdeadbeef.toInt, 0xaa22d41a.toInt),
    ("testy", 0xdeadbeef.toInt, 0x84f5f623.toInt),
    ("testy1", 0xdeadbeef.toInt, 0x09ed28e9),
    ("testy12", 0xdeadbeef.toInt, 0x22467835),
    ("testy123", 0xdeadbeef.toInt, 0xd633060d.toInt),
    ("special characters a\u00e7b\u2193c", 0xdeadbeef.toInt, 0xf7fdd8a2.toInt),
    ("The quick brown fox jumps over the lazy dog", 0xdeadbeef.toInt, 0x3a7b3f4d),
    ("", 0x00000001, 0x514e28b7),
    (" ", 0x00000001, 0x4f0f7132),
    ("t", 0x00000001, 0x5db1831e),
    ("te", 0x00000001, 0xd248bb2e.toInt),
    ("tes", 0x00000001, 0xd432eb74.toInt),
    ("test", 0x00000001, 0x99c02ae2.toInt),
    ("testy", 0x00000001, 0xc5b2dc1e.toInt),
    ("testy1", 0x00000001, 0x33925ceb),
    ("testy12", 0x00000001, 0xd92c9f23.toInt),
    ("testy123", 0x00000001, 0x3bc1712d),
    ("special characters a\u00e7b\u2193c", 0x00000001, 0x293327b5),
    ("The quick brown fox jumps over the lazy dog", 0x00000001, 0x78e69e27)
  )

  testCases.zipWithIndex.foreach { case ((input, seed, expected), idx) =>
    test(s"murmur3 test case $idx: '${input.take(40)}' with seed 0x${(seed.toLong & 0xFFFFFFFFL).toHexString}") {
      val result = Murmur3.hashString(input, seed)
      assert(result == expected,
        s"Expected 0x${(expected.toLong & 0xFFFFFFFFL).toHexString}, got 0x${(result.toLong & 0xFFFFFFFFL).toHexString}")
    }
  }
}
