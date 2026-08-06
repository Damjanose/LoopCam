package expo.modules.loopcamrecorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speed rules, pinned down without a device.
 *
 * This file is also the cross-platform contract: `ios/SpeedStyle.swift` has no
 * test target of its own, so it is checked against this table by inspection. A
 * change here that is not mirrored there is a change that will produce different
 * footage on the two platforms.
 */
class SpeedStyleTest {

  // --- validation ---------------------------------------------------------

  @Test
  fun `a fix with no speed solution is rejected`() {
    // -1 is how both platforms say "no solution yet", not a slow reverse.
    assertNull(SpeedStyle.validate(speedMps = -1.0, speedAccuracyMps = 1.0))
  }

  @Test
  fun `a negative accuracy is rejected rather than clamped`() {
    assertNull(SpeedStyle.validate(speedMps = 20.0, speedAccuracyMps = -1.0))
  }

  @Test
  fun `a fix whose own error estimate is too large is rejected`() {
    assertNull(SpeedStyle.validate(speedMps = 20.0, speedAccuracyMps = 5.0))
    assertEquals(20.0, SpeedStyle.validate(20.0, SpeedStyle.MAX_SPEED_ERROR_MPS)!!, 1e-9)
  }

  @Test
  fun `parked jitter is clamped to a standstill`() {
    assertEquals(0.0, SpeedStyle.validate(0.4, 1.0)!!, 1e-9)
  }

  @Test
  fun `a reading above the standstill threshold is passed through untouched`() {
    assertEquals(0.8, SpeedStyle.validate(0.8, 1.0)!!, 1e-9)
  }

  // --- staleness ----------------------------------------------------------

  @Test
  fun `a fix older than the staleness window no longer describes the frame`() {
    val now = 1_786_012_327_000L
    assertFalse(SpeedStyle.isFresh(timestampMs = now - 4_000L, nowMs = now))
    assertTrue(SpeedStyle.isFresh(timestampMs = now - 2_000L, nowMs = now))
  }

  // --- formatting ---------------------------------------------------------

  @Test
  fun `the speed field is the same width whatever the value`() {
    val widths = listOf(
      SpeedStyle.format(null, SpeedUnit.KMH),
      SpeedStyle.format(0.0, SpeedUnit.KMH),
      SpeedStyle.format(20.0, SpeedUnit.KMH),
      SpeedStyle.format(30.0, SpeedUnit.KMH),
      SpeedStyle.format(20.0, SpeedUnit.KMH, derived = true),
    ).map { it.length }.distinct()
    assertEquals(listOf(8), widths)
  }

  @Test
  fun `the unit is drawn, never implied`() {
    assertEquals(" 72 km/h", SpeedStyle.format(20.0, SpeedUnit.KMH))
    assertEquals(" 45 mph", SpeedStyle.format(20.0, SpeedUnit.MPH))
  }

  @Test
  fun `an unknown speed reads as two dashes, not as zero`() {
    assertEquals(" -- km/h", SpeedStyle.format(null, SpeedUnit.KMH))
    assertEquals("  0 km/h", SpeedStyle.format(0.0, SpeedUnit.KMH))
  }

  @Test
  fun `a derived reading is marked so it cannot pass as a measurement`() {
    assertEquals(" 72~km/h", SpeedStyle.format(20.0, SpeedUnit.KMH, derived = true))
    // Nothing was derived, so nothing may be marked.
    assertEquals(" -- km/h", SpeedStyle.format(null, SpeedUnit.KMH, derived = true))
  }

  // --- the position-differencing fallback (§Part 5) -----------------------

  @Test
  fun `haversine measures a known distance`() {
    // One degree of latitude at the equator is ~111.19 km, and the same
    // north-south wherever it is measured.
    assertEquals(111_195.0, SpeedStyle.haversineM(0.0, 0.0, 1.0, 0.0), 50.0)
    // A degree of *longitude* shrinks with the cosine of the latitude, which is
    // the correction a flat-earth approximation is most likely to get wrong.
    assertEquals(78_626.0, SpeedStyle.haversineM(45.0, 0.0, 45.0, 1.0), 100.0)
  }

  @Test
  fun `haversine is zero for a point that has not moved`() {
    assertEquals(0.0, SpeedStyle.haversineM(41.3275, 19.8187, 41.3275, 19.8187), 1e-9)
  }

  @Test
  fun `a derived reading over one second is a plausible urban speed`() {
    // ~20 m of northward travel in a second: 20 m/s, or 72 km/h.
    val metres = SpeedStyle.haversineM(41.3275, 19.8187, 41.327_680, 19.8187)
    assertEquals(20.0, metres, 1.0)
  }

  @Test
  fun `an unknown unit falls back rather than throwing`() {
    assertEquals(SpeedUnit.KMH, SpeedUnit.from("furlongs-per-fortnight"))
    assertEquals(SpeedUnit.MPH, SpeedUnit.from("mph"))
  }
}
