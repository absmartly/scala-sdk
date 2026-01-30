package com.absmartly.sdk

/**
 * Murmur3_32 hash implementation
 *
 * Matches Rust/JavaScript reference implementations exactly.
 * CRITICAL: All operations are UNSIGNED 32-bit, byte order is LITTLE-ENDIAN
 */
object Murmur3 {
  private val C1: Int = 0xcc9e2d51
  private val C2: Int = 0x1b873593
  private val C3: Int = 0xe6546b64

  /**
   * Compute Murmur3_32 hash - matches JavaScript/Rust exactly
   */
  def hash32(key: Array[Byte], seed: Int = 0): Int = {
    var hash = seed
    val len = key.length
    val n = len & ~3  // Process full 4-byte blocks

    // Process 4-byte chunks (little-endian)
    var i = 0
    while (i < n) {
      val chunk = (
        (key(i) & 0xff) |
        ((key(i + 1) & 0xff) << 8) |
        ((key(i + 2) & 0xff) << 16) |
        ((key(i + 3) & 0xff) << 24)
      )
      hash ^= scramble32(chunk)
      hash = rotl32(hash, 13)
      hash = hash * 5 + C3
      i += 4
    }

    // Process remaining bytes
    var remaining = 0
    (len & 3) match {
      case 3 =>
        remaining ^= (key(i + 2) & 0xff) << 16
        remaining ^= (key(i + 1) & 0xff) << 8
        remaining ^= (key(i) & 0xff)
        hash ^= scramble32(remaining)
      case 2 =>
        remaining ^= (key(i + 1) & 0xff) << 8
        remaining ^= (key(i) & 0xff)
        hash ^= scramble32(remaining)
      case 1 =>
        remaining ^= (key(i) & 0xff)
        hash ^= scramble32(remaining)
      case _ => // 0 bytes
    }

    // Finalization
    hash ^= len
    fmix32(hash)
  }

  private def scramble32(block: Int): Int = {
    rotl32(block * C1, 15) * C2
  }

  private def fmix32(h: Int): Int = {
    var hash = h
    hash ^= hash >>> 16
    hash = hash * 0x85ebca6b
    hash ^= hash >>> 13
    hash = hash * 0xc2b2ae35
    hash ^= hash >>> 16
    hash
  }

  private def rotl32(x: Int, n: Int): Int = {
    (x << n) | (x >>> (32 - n))
  }

  /**
   * Hash a string (UTF-8 bytes)
   */
  def hashString(str: String, seed: Int = 0): Int = {
    hash32(str.getBytes("UTF-8"), seed)
  }
}



