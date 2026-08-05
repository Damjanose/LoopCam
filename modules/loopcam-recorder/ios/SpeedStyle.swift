import Foundation

/// The one place the rules for a burned-in speed are defined: when a fix is
/// believed, when a car counts as stopped, and how the number is written.
///
/// Mirrors `android/…/SpeedStyle.kt`, constant for constant, for the same reason
/// `WatermarkStyle` is: two platforms must not disagree about when a car is
/// stopped, and footage from an iPhone and footage from a Pixel of the same
/// drive must not carry different numbers.
///
/// Everything here is a pure function of its arguments. That is deliberate — the
/// validation and clamping rules are the part of phase 5 that can be pinned down
/// without a device, and the Kotlin `SpeedStyleTest` does exactly that; this file
/// is checked against that table by inspection, since the pod has no test target.
enum SpeedStyle {
  /// Below this, report 0 rather than the reading.
  ///
  /// Parked with a clear sky, Doppler speed wanders around 0–1 m/s. Rendered
  /// honestly the stamp flickers between "0" and "3 km/h" on a stationary car,
  /// which reads as a broken instrument. 0.6 m/s is roughly the ceiling of that
  /// jitter and still below walking pace, so it can never suppress a real
  /// reading.
  static let standstillMps: Double = 0.6

  /// Older than this and the frame is stamped `--` instead of a number.
  ///
  /// Three fix intervals: enough to ride out one dropped update, short enough
  /// that a tunnel shows as unknown almost immediately. A dashcam clip that
  /// displays 90 km/h because that was true forty seconds ago, before the
  /// tunnel, is worse than one that admits it didn't know.
  static let maxFixAge: TimeInterval = 3.0

  /// One fix per second. §6 budgets 1 fix / 2–3 s for the *sidecar*, but the
  /// watermark is on screen continuously; 1 Hz is the slowest rate at which a
  /// burned-in speed does not visibly lag the road.
  static let fixInterval: TimeInterval = 1.0

  /// Reject fixes whose own error estimate exceeds ~7 km/h.
  static let maxSpeedErrorMps: Double = 2.0

  /// How many consecutive otherwise-valid fixes may arrive without a usable
  /// speed before the tracker falls back to differencing positions (§Part 5).
  ///
  /// Five seconds at 1 Hz. Short enough that a device which will never produce
  /// a Doppler solution does not spend a whole drive stamping `--`; long enough
  /// that an ordinary run of rejected fixes cannot trip it.
  static let deriveAfterMissingFixes = 5

  /// How many derived readings are averaged before one is shown.
  ///
  /// A raw haversine difference between consecutive positions inherits the
  /// position drift of both, and at 1 Hz that reads as a number jumping by tens
  /// between frames. Three samples is the shortest window that produces
  /// something legible without lagging a real acceleration by more than it is
  /// worth.
  static let derivedWindow = 3

  /// Ignore a derived reading above this. 100 m/s is 360 km/h — not a speed a
  /// car reaches, so it is a position glitch being divided by a short interval.
  static let maxDerivedMps: Double = 100

  /// Mean Earth radius, for the haversine fallback.
  private static let earthRadiusM: Double = 6_371_000

  /// Great-circle distance in metres between two coordinates.
  ///
  /// Haversine rather than a flat-earth approximation: the approximation is
  /// fine at these distances, but it needs a latitude-dependent correction that
  /// is exactly the kind of thing that is wrong at one edge of the map and
  /// never noticed.
  static func haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double) -> Double {
    let toRadians = Double.pi / 180
    let dLat = (lat2 - lat1) * toRadians
    let dLon = (lon2 - lon1) * toRadians
    let a =
      pow(sin(dLat / 2), 2)
      + cos(lat1 * toRadians) * cos(lat2 * toRadians) * pow(sin(dLon / 2), 2)
    return 2 * earthRadiusM * asin(min(1, sqrt(a)))
  }

  /// Width of the number slot. Three digits covers every speed a car reaches in
  /// either unit; reserving it means the plate cannot grow by a glyph between 99
  /// and 100 km/h, which would visibly twitch on every crossing.
  private static let numberWidth = 3

  /// Shown in the number slot when there is no trustworthy reading.
  private static let unknown = "--"

  /// Validate one fix's speed and clamp it, or reject it outright.
  ///
  /// Both platforms funnel their own validity signals through here so the rules
  /// live in one place: iOS passes `location.speed` / `location.speedAccuracy`
  /// directly (both are negative when CoreLocation has no solution), Android
  /// passes `-1` for whichever of `hasSpeed()` / `hasSpeedAccuracy()` says is
  /// absent.
  ///
  /// Returns nil — meaning "not currently known" — rather than a best guess.
  static func validate(speedMps: Double, speedAccuracyMps: Double) -> Double? {
    // Negative on either is a rejection, not a clamp: it is the platforms' way
    // of saying no solution exists, and a clamp would turn that into 0.
    guard speedMps >= 0, speedAccuracyMps >= 0 else { return nil }
    guard speedAccuracyMps <= maxSpeedErrorMps else { return nil }
    return speedMps < standstillMps ? 0 : speedMps
  }

  /// Whether a fix taken at `timestamp` still describes the frame being drawn.
  static func isFresh(timestamp: Date, now: Date) -> Bool {
    now.timeIntervalSince(timestamp) <= maxFixAge
  }

  /// The speed field as it is burned into the frame — always the same width for
  /// a given unit, whatever the value.
  ///
  /// The layout is a right-aligned number slot, a one-character marker slot, and
  /// the unit: `" 72 km/h"`, `"  0 km/h"`, `" -- km/h"`, `" 72~km/h"`. The
  /// marker slot doubles as the space before the unit, so a derived reading
  /// (§Part 5) can be flagged without the plate changing size.
  ///
  /// The unit is drawn, never implied: a bare number burned into evidence is
  /// ambiguous across a border.
  static func format(mps: Double?, unit: SpeedUnit, derived: Bool = false) -> String {
    let number: String
    if let mps {
      number = String(min(999, max(0, Int((mps * unit.perMps).rounded()))))
    } else {
      number = unknown
    }
    // A marker on an unknown reading would claim we derived a number we do not
    // have, so it is suppressed here rather than only at the call site.
    let marker = (derived && mps != nil) ? "~" : " "
    let padding = String(repeating: " ", count: max(0, numberWidth - number.count))
    return padding + number + marker + unit.label
  }
}
