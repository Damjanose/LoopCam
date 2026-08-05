import CoreLocation
import Foundation
import os

/// A speed reading at an instant. A nil `speedMps` means "not currently known".
struct SpeedSample {
  /// Metres per second, already validated and clamped. Nil when unknown.
  let speedMps: Double?
  let latitude: Double
  let longitude: Double
  /// Fix time, epoch ms — the receiver's clock, not when we processed it.
  let timestampMs: Double
  /// Horizontal accuracy in metres, for the sidecar's benefit.
  let accuracyM: Double
  /// True when `speedMps` was inferred from two positions rather than measured
  /// (§Part 5). Carried all the way into the sidecar and the watermark so an
  /// inference can never be mistaken for a measurement.
  var derived: Bool = false
}

/// Why the burned-in speed is reading `--`. Mirrors `LocationStatus` in JS.
enum LocationStatus: String {
  case ok
  case noFix
  case coarseOnly
  case denied
  case disabled
}

/// The location client, and the only place a speed comes from.
///
/// Speed is read from the fix, never computed from two positions. Both platforms
/// derive the `speed` field from the Doppler shift on the GNSS carrier — a
/// direct measurement of velocity along the line of sight to each satellite,
/// typically accurate to under 0.5 m/s even while the *position* is drifting
/// several metres. Differencing two positions inherits that drift and then
/// divides it by the sample interval: ±5 m of ordinary urban position error
/// across a 1 s gap reads as ±18 km/h of speed that isn't there. For a number
/// that gets burned irreversibly into evidence, that difference is the whole
/// argument.
///
/// A singleton rather than an injected dependency, and for the same reason
/// `WatermarkRenderer.shared` is one: its two consumers sit in unrelated object
/// graphs — the compositor inside `AVSegmentRecorder`, the sidecar up in
/// `ClipMerger` — and threading one reference through four initialisers to reach
/// both would be noise.
///
/// Deliberately not routed through JS. The watermark needs this value at 30 fps
/// and the sidecar needs it during a merge; a round trip across the bridge would
/// make the burned-in speed depend on the JS thread being responsive, which
/// during a merge it may not be.
final class LocationTracker: NSObject, CLLocationManagerDelegate {
  static let shared = LocationTracker()

  /// How far past the buffer window samples are kept.
  ///
  /// The sidecar covers the merged clips' own time range, and the oldest clip in
  /// a full ring starts one whole window ago; a margin means a sample landing
  /// fractionally before that boundary is still there to describe it.
  private static let retentionMargin: TimeInterval = 60

  private let manager = CLLocationManager()

  /// Read by the compositor on the capture queue every frame; written by the
  /// delegate on the main queue. An `os_unfair_lock` rather than a serial
  /// queue: the read is on the hot path and must never block a frame.
  private var lock = os_unfair_lock()
  private var latest: SpeedSample?
  /// Everything seen since `start`, for the sidecar. Bounded by `retention`.
  private var samples: [SpeedSample] = []

  private var retention: TimeInterval = 180
  private var enabled = true
  private var running = false

  /// §Part 5 — the fallback that isn't the primary.
  ///
  /// Set once, after enough consecutive otherwise-valid fixes have arrived with
  /// no usable speed that the receiver is clearly not going to produce a
  /// Doppler solution at all. Never unset within a session: a device that
  /// flickered between measured and derived readings would put a `~` on and off
  /// the plate every few seconds, which reads as a fault rather than a caveat.
  ///
  /// Written only on the delivery queue, like the two below.
  private var derivedMode = false
  private var missingSpeedRun = 0
  /// The last few derived readings, averaged before any of them is shown.
  private var derivedWindow: [Double] = []

  private override init() {
    super.init()
    manager.delegate = self
  }

  /// Start tracking for a recording session. Idempotent.
  func start(config: RecorderConfig) {
    configure(config)
    guard config.locationTaggingEnabled, !running else { return }
    guard authorized else { return }

    os_unfair_lock_lock(&lock)
    samples = []
    latest = nil
    os_unfair_lock_unlock(&lock)

    // A fresh session re-tests the primary path. Carrying derivedMode across
    // sessions would mean one bad drive under a bridge permanently downgraded
    // the app on a device that measures speed perfectly well.
    derivedMode = false
    missingSpeedRun = 0
    derivedWindow = []

    // The one accuracy tier that keeps the GNSS chip in a continuous-tracking
    // mode rather than duty-cycling it — which is what makes the Doppler
    // solution available on every fix instead of intermittently.
    manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
    // Tells CoreLocation the receiver is in a car: it stops applying the
    // pedestrian-oriented filtering that smooths away real acceleration.
    manager.activityType = .automotiveNavigation
    manager.distanceFilter = kCLDistanceFilterNone
    // CoreLocation will otherwise stop updates when it decides the device is
    // stationary — which for a dashcam parked at a light is exactly wrong.
    manager.pausesLocationUpdatesAutomatically = false
    // Without this iOS stops delivering fixes the moment the app backgrounds,
    // and every frame recorded with the screen off would stamp `--`. Requires
    // `location` in UIBackgroundModes; setting it without that traps.
    manager.allowsBackgroundLocationUpdates = true
    manager.startUpdatingLocation()
    running = true
  }

  func stop() {
    guard running else { return }
    manager.stopUpdatingLocation()
    // Set back before the app leaves the recording path: leaving it true costs
    // the location-services indicator in the status bar for a session that is
    // no longer using it.
    manager.allowsBackgroundLocationUpdates = false
    running = false

    os_unfair_lock_lock(&lock)
    latest = nil
    samples = []
    os_unfair_lock_unlock(&lock)
  }

  /// Apply a config change without restarting. Retention follows the buffer
  /// window, and turning tagging off mid-drive stops the client immediately
  /// rather than at the next Play.
  func configure(_ config: RecorderConfig) {
    retention = config.bufferDurationSec + Self.retentionMargin
    enabled = config.locationTaggingEnabled
    if !enabled, running { stop() }
  }

  /// Ask for permission. Separate from `start` on purpose: `start` runs when
  /// Play is pressed, and throwing a system dialog at someone the instant they
  /// begin recording — quite possibly while already driving — is not where that
  /// question belongs. `LoopcamRecorderModule.requestPermissions` asks it
  /// alongside camera and microphone instead.
  func requestAuthorization() {
    manager.requestWhenInUseAuthorization()
  }

  /// The compositor's read: the newest fix, or nil when it is too old to
  /// describe the frame being drawn.
  ///
  /// A lock, a struct copy, and an arithmetic comparison. It does not allocate,
  /// format, or touch the location client — this runs 30 times a second on the
  /// capture queue.
  func currentSpeed(now: Date = Date()) -> SpeedSample? {
    os_unfair_lock_lock(&lock)
    defer { os_unfair_lock_unlock(&lock) }
    guard let latest else { return nil }
    let timestamp = Date(timeIntervalSince1970: latest.timestampMs / 1000)
    return SpeedStyle.isFresh(timestamp: timestamp, now: now) ? latest : nil
  }

  /// The samples describing a merged window, for the sidecar.
  ///
  /// Non-destructive: a merge that fails must not have consumed the samples that
  /// would describe a later retry. Bounding is `retention`'s job instead.
  func samplesBetween(fromMs: Double, toMs: Double) -> [SpeedSample] {
    os_unfair_lock_lock(&lock)
    defer { os_unfair_lock_unlock(&lock) }
    return samples.filter { $0.timestampMs >= fromMs && $0.timestampMs <= toMs }
  }

  /// Why the speed is reading `--`, for Settings to explain.
  func status(now: Date = Date()) -> LocationStatus {
    if !enabled { return .disabled }
    switch manager.authorizationStatus {
    case .denied, .restricted, .notDetermined:
      return .denied
    default:
      break
    }
    // Android 12+ has an explicit approximate-location grant; iOS's equivalent
    // is precise location being switched off for the app. A reduced-accuracy
    // fix carries no usable speed either way.
    if manager.accuracyAuthorization != .fullAccuracy { return .coarseOnly }
    return currentSpeed(now: now)?.speedMps != nil ? .ok : .noFix
  }

  private var authorized: Bool {
    switch manager.authorizationStatus {
    case .authorizedAlways, .authorizedWhenInUse: return true
    default: return false
    }
  }

  // MARK: - CLLocationManagerDelegate

  func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
    guard let location = locations.last else { return }

    // `speed` is -1 before a solution exists and `speedAccuracy` is negative
    // when the value is not trustworthy at all. Both are rejections, not
    // clamps, and SpeedStyle is where that rule lives.
    var measured = SpeedStyle.validate(
      speedMps: location.speed,
      speedAccuracyMps: location.speedAccuracy
    )
    var derived = false

    if measured != nil {
      missingSpeedRun = 0
    } else {
      missingSpeedRun += 1
      if missingSpeedRun >= SpeedStyle.deriveAfterMissingFixes {
        // The receiver is not going to hand us a Doppler solution. A number
        // inferred from two positions is worse, but it is better than a field
        // that is blank for the whole drive — and it is marked, so footage
        // produced this way cannot be mistaken for a measured reading.
        if !derivedMode {
          NSLog(
            "LoopCam/Location: no usable Doppler speed after \(missingSpeedRun) fixes; "
              + "deriving from position"
          )
          derivedMode = true
        }
        if let inferred = derive(from: location) {
          measured = inferred
          derived = true
        }
      }
    }

    let sample = SpeedSample(
      speedMps: measured,
      latitude: location.coordinate.latitude,
      longitude: location.coordinate.longitude,
      timestampMs: location.timestamp.timeIntervalSince1970 * 1000,
      accuracyM: location.horizontalAccuracy,
      derived: derived
    )

    os_unfair_lock_lock(&lock)
    latest = sample
    samples.append(sample)
    prune(nowMs: sample.timestampMs)
    os_unfair_lock_unlock(&lock)
  }

  /// §Part 5 — speed from `haversine(p₁, p₂) / Δt`, smoothed.
  ///
  /// Only ever reached in `derivedMode`. Returns nil until the moving average
  /// has enough readings to be worth showing: a single difference between two
  /// drifting positions is not a speed, it is noise with a unit.
  ///
  /// Runs on the delivery queue, which is the only writer of `derivedWindow`
  /// and `missingSpeedRun`.
  private func derive(from location: CLLocation) -> Double? {
    os_unfair_lock_lock(&lock)
    let previous = latest
    os_unfair_lock_unlock(&lock)
    guard let previous else { return nil }

    let deltaSec =
      location.timestamp.timeIntervalSince1970 - previous.timestampMs / 1000
    // A zero or negative interval is a duplicate or an out-of-order fix, and
    // dividing by it produces an infinity that would burn straight into the
    // footage.
    guard deltaSec > 0 else { return nil }

    let metres = SpeedStyle.haversineM(
      lat1: previous.latitude,
      lon1: previous.longitude,
      lat2: location.coordinate.latitude,
      lon2: location.coordinate.longitude
    )
    let raw = metres / deltaSec
    guard raw <= SpeedStyle.maxDerivedMps else { return nil }

    derivedWindow.append(raw)
    if derivedWindow.count > SpeedStyle.derivedWindow {
      derivedWindow.removeFirst(derivedWindow.count - SpeedStyle.derivedWindow)
    }
    guard derivedWindow.count == SpeedStyle.derivedWindow else { return nil }

    let mean = derivedWindow.reduce(0, +) / Double(derivedWindow.count)
    // The same standstill clamp the measured path gets, and for the same
    // reason: position drift on a parked car is exactly what this computes.
    return mean < SpeedStyle.standstillMps ? 0 : mean
  }

  func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
    // A failed update is not a reason to freeze on the last value: the staleness
    // check in `currentSpeed` will fall to `--` on its own within maxFixAge.
    NSLog("LoopCam/Location: update failed — \(error.localizedDescription)")
  }

  /// Drop samples older than the window they could ever describe.
  ///
  /// At 1 Hz and a 15-minute buffer this holds ~900 samples — a few tens of
  /// kilobytes — but it has to be bounded, because the recorder is expected to
  /// run for hours.
  ///
  /// Caller holds `lock`.
  private func prune(nowMs: Double) {
    let cutoff = nowMs - retention * 1000
    if let keepFrom = samples.firstIndex(where: { $0.timestampMs >= cutoff }) {
      if keepFrom > 0 { samples.removeFirst(keepFrom) }
    } else {
      samples = []
    }
  }
}
