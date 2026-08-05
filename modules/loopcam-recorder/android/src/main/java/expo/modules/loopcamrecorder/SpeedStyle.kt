package expo.modules.loopcamrecorder

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The one place the rules for a burned-in speed are defined: when a fix is
 * believed, when a car counts as stopped, and how the number is written.
 *
 * Mirrored by `ios/SpeedStyle.swift`, constant for constant, for the same reason
 * [WatermarkStyle] is: two platforms must not disagree about when a car is
 * stopped, and footage from an iPhone and footage from a Pixel of the same drive
 * must not carry different numbers.
 *
 * Everything here is a pure function of its arguments. That is deliberate — the
 * validation and clamping rules are the part of phase 5 that can be pinned down
 * without a device, and `SpeedStyleTest` does exactly that.
 */
internal object SpeedStyle {
  /**
   * Below this, report 0 rather than the reading.
   *
   * Parked with a clear sky, Doppler speed wanders around 0–1 m/s. Rendered
   * honestly the stamp flickers between "0" and "3 km/h" on a stationary car,
   * which reads as a broken instrument. 0.6 m/s is roughly the ceiling of that
   * jitter and still below walking pace, so it can never suppress a real
   * reading.
   */
  const val STANDSTILL_MPS = 0.6

  /**
   * Older than this and the frame is stamped `--` instead of a number.
   *
   * Three fix intervals: enough to ride out one dropped update, short enough
   * that a tunnel shows as unknown almost immediately. A dashcam clip that
   * displays 90 km/h because that was true forty seconds ago, before the
   * tunnel, is worse than one that admits it didn't know.
   */
  const val MAX_FIX_AGE_MS = 3_000L

  /**
   * One fix per second. §6 budgets 1 fix / 2–3 s for the *sidecar*, but the
   * watermark is on screen continuously; 1 Hz is the slowest rate at which a
   * burned-in speed does not visibly lag the road.
   */
  const val FIX_INTERVAL_MS = 1_000L

  /** Reject fixes whose own error estimate exceeds ~7 km/h. */
  const val MAX_SPEED_ERROR_MPS = 2.0

  /**
   * How many consecutive otherwise-valid fixes may arrive without a usable
   * speed before the tracker falls back to differencing positions (§Part 5).
   *
   * Five seconds at 1 Hz. Short enough that a device which will never produce a
   * Doppler solution does not spend a whole drive stamping `--`; long enough
   * that an ordinary run of rejected fixes — a bad patch of sky, a fix or two
   * whose error estimate was too wide — cannot trip it.
   */
  const val DERIVE_AFTER_MISSING_FIXES = 5

  /**
   * How many derived readings are averaged before one is shown.
   *
   * A raw haversine difference between consecutive positions inherits the
   * position drift of both, and at 1 Hz that reads as a number jumping by tens
   * between frames. Three samples is the shortest window that produces
   * something legible without lagging a real acceleration by more than it is
   * worth.
   */
  const val DERIVED_WINDOW = 3

  /**
   * Ignore a derived reading above this. 100 m/s is 360 km/h — not a speed a
   * car reaches, so it is a position glitch being divided by a short interval.
   */
  const val MAX_DERIVED_MPS = 100.0

  /** Mean Earth radius, for the haversine fallback. */
  private const val EARTH_RADIUS_M = 6_371_000.0

  /**
   * Great-circle distance in metres between two coordinates.
   *
   * Haversine rather than a flat-earth approximation: the approximation is
   * fine at these distances, but it needs a latitude-dependent correction that
   * is exactly the kind of thing that is wrong at one edge of the map and never
   * noticed.
   */
  fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
  }

  /**
   * Width of the number slot. Three digits covers every speed a car reaches in
   * either unit; reserving it means the plate cannot grow by a glyph between 99
   * and 100 km/h, which would visibly twitch on every crossing.
   */
  private const val NUMBER_WIDTH = 3

  /** Shown in the number slot when there is no trustworthy reading. */
  private const val UNKNOWN = "--"

  /**
   * Validate one fix's speed and clamp it, or reject it outright.
   *
   * Both platforms funnel their own validity signals through here so the rules
   * live in one place: iOS passes `location.speed` / `location.speedAccuracy`
   * directly (both are negative when CoreLocation has no solution), Android
   * passes `-1.0` for whichever of the two `hasSpeed()` / `hasSpeedAccuracy()`
   * says is absent.
   *
   * Returns null — meaning "not currently known" — rather than a best guess.
   */
  fun validate(speedMps: Double, speedAccuracyMps: Double): Double? {
    // Negative on either is a rejection, not a clamp: it is the platforms'
    // way of saying no solution exists, and a clamp would turn that into 0.
    if (speedMps < 0 || speedAccuracyMps < 0) return null
    if (speedAccuracyMps > MAX_SPEED_ERROR_MPS) return null
    return if (speedMps < STANDSTILL_MPS) 0.0 else speedMps
  }

  /** Whether a fix taken at [timestampMs] still describes the frame being drawn. */
  fun isFresh(timestampMs: Long, nowMs: Long): Boolean =
    nowMs - timestampMs <= MAX_FIX_AGE_MS

  /**
   * The speed field as it is burned into the frame — always the same width for
   * a given unit, whatever the value.
   *
   * The layout is a right-aligned number slot, a one-character marker slot, and
   * the unit: `" 72 km/h"`, `"  0 km/h"`, `" -- km/h"`, `" 72~km/h"`. The
   * marker slot doubles as the space before the unit, so a derived reading
   * (§Part 5) can be flagged without the plate changing size.
   *
   * The unit is drawn, never implied: a bare number burned into evidence is
   * ambiguous across a border.
   */
  fun format(mps: Double?, unit: SpeedUnit, derived: Boolean = false): String {
    val number = if (mps == null) {
      UNKNOWN
    } else {
      (mps * unit.perMps).roundToInt().coerceIn(0, 999).toString()
    }
    // A marker on an unknown reading would claim we derived a number we do not
    // have, so it is suppressed there rather than only at the call site.
    val marker = if (derived && mps != null) '~' else ' '
    return number.padStart(NUMBER_WIDTH) + marker + unit.label
  }
}
