package com.absmartly.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Variant assignment using Murmur3_32 hash
 *
 * CRITICAL: Unit must be hashed with Utils.hashUnit() BEFORE creating VariantAssigner
 */
class VariantAssigner(hashedUnit: String) {

  /**
   * Assign a variant based on split probabilities
   *
   * Algorithm:
   * 1. Hash the unit ID with murmur3_32(unit, 0)
   * 2. Create buffer: [seedLo (4 bytes LE), seedHi (4 bytes LE), unitHash (4 bytes LE)]
   * 3. Hash buffer with murmur3_32(buffer, 0)
   * 4. Calculate probability = hash / 0xFFFFFFFF  (NOT 0x100000000!)
   * 5. Return first variant where cumulative split >= probability
   *
   * @param split Cumulative probability array [0.0, p1, p2, ..., 1.0]
   * @param seedHi High 32 bits of seed
   * @param seedLo Low 32 bits of seed
   * @return Variant index (0-based)
   */
  def assign(split: List[Double], seedHi: Int, seedLo: Int): Int = {
    // Step 1: Hash the unit ID
    val unitHash = Murmur3.hashString(hashedUnit, 0)

    // Step 2: Create buffer with seeds and unit hash (little-endian)
    val buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(seedLo)
    buffer.putInt(seedHi)
    buffer.putInt(unitHash)

    // Step 3: Hash the buffer
    val hash = Murmur3.hash32(buffer.array(), 0)

    // Step 4: Calculate probability
    // CRITICAL: Divide by 0xFFFFFFFF (not 0x100000000)
    val probability = (hash & 0xFFFFFFFFL).toDouble / 0xFFFFFFFFL.toDouble

    // Step 5: Choose variant
    Utils.chooseVariant(split, probability)
  }
}
