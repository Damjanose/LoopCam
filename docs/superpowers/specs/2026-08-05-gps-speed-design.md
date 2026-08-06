# GPS speed: reading it accurately, and burning it into the footage

The recorder already promises speed. `RecorderConfig.locationTaggingEnabled`
defaults to `true`, `SavedClip.metadataUri` is part of the public type, and both
`ClipMerger`s call a `writeMetadataSidecar` that is a `TODO(phase-5)` no-op. This
document specifies phase 5: where the speed number comes from, why it comes from
there and not from arithmetic, and how it reaches the two places that need it —
the burned-in watermark and the sidecar JSON.

Section references (`§6`, `§7.1`, …) point at
[`docs/LoopCam_Development_Plan.pdf`](../../LoopCam_Development_Plan.pdf).
The per-frame drawing path this hooks into is specified in
[the watermark and standby-preview design](./2026-08-05-watermark-and-standby-preview-design.md).

## Decisions taken before the design

**Speed is read from the fix, never computed from two positions.** Both
platforms report a speed field on every location update, and both derive it from
the Doppler shift on the GNSS carrier signal — a direct measurement of velocity
along the line of sight to each satellite, not an inference from where the
receiver thinks it is. Typical error is under 0.5 m/s (under 2 km/h) even while
the *position* is drifting several metres. Differencing two positions inherits
that drift and then divides it by the sample interval: ±5 m of ordinary urban
position error across a 1 s gap reads as ±18 km/h of speed that isn't there. For
a number that gets burned irreversibly into evidence, that difference is the
whole argument.

**The location client lives in the native module, not in JS.** The two consumers
are both native: the watermark is drawn per frame inside
`WatermarkOverlay.kt` / `WatermarkRenderer.swift`, and the sidecar is written by
`ClipMerger` at Save. Routing fixes through `expo-location` into JS and back
across the bridge would add a round trip and a serialisation hop to a value the
compositor needs at 30 fps, and would make the burned-in speed depend on the JS
thread being responsive — which, during a merge, it may not be. The module owns
a `LocationTracker` and JS never sees an individual fix.

**Doppler speed, not fused speed, on Android.** `FusedLocationProviderClient`
will happily hand back a fix derived from Wi-Fi or cell towers, and the `speed`
on such a fix is either absent or garbage. The tracker requests
`PRIORITY_HIGH_ACCURACY` and then *discards any fix that fails `hasSpeed()`* —
the accuracy of what gets burned in matters more than always having a number.

**No number is better than a wrong number.** When the fix is stale, the speed is
flagged invalid, or the receiver has no lock, the watermark shows `--` rather
than the last known value. A dashcam clip that displays 90 km/h because that was
true forty seconds ago, before the tunnel, is worse than one that admits it
didn't know.

**Standstill is clamped to zero.** Parked with a clear sky, Doppler speed wanders
around 0–1 m/s. Rendered honestly, the stamp flickers between "0" and "3 km/h"
on a stationary car. A threshold below which the reading is reported as zero is
the only version that looks like a working instrument.

---

## Part 1 — `LocationTracker`

One class per platform, mirrored the way `WatermarkStyle` is mirrored, owning
the platform location client and exposing exactly two things: a cheap snapshot
for the compositor, and an accumulating sample log for the sidecar.

### The snapshot type

```ts
/** A speed reading at an instant. `null` speed means "not currently known". */
interface SpeedSample {
  /** Metres per second, already validated and clamped. `null` when unknown. */
  speedMps: number | null;
  latitude: number;
  longitude: number;
  /** Fix time, epoch ms — the receiver's clock, not when we processed it. */
  timestampMs: number;
  /** Horizontal accuracy in metres, for the sidecar's benefit. */
  accuracyM: number;
}
```

### iOS — `ios/LocationTracker.swift`

```swift
import CoreLocation

final class LocationTracker: NSObject, CLLocationManagerDelegate {
  /// Read by the compositor on the video queue every frame; written by the
  /// delegate on the main queue. `os_unfair_lock` rather than a serial queue:
  /// the read is on the hot path and must never block a frame.
  private var latest: SpeedSample?
  private var lock = os_unfair_lock()

  /// Everything seen since the last reset, for the sidecar. Bounded by the
  /// buffer window, so it cannot grow without limit.
  private var samples: [SpeedSample] = []

  private let manager = CLLocationManager()

  func start() {
    manager.delegate = self
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
    manager.requestWhenInUseAuthorization()
    manager.startUpdatingLocation()
  }

  func locationManager(_ m: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
    guard let loc = locs.last else { return }

    // `speed` is -1 before a solution exists; `speedAccuracy` is negative when
    // the value is not trustworthy at all. Both are rejections, not clamps.
    let valid = loc.speed >= 0 && loc.speedAccuracy >= 0
    let clamped: Double? = valid
      ? (loc.speed < SpeedStyle.standstillMps ? 0 : loc.speed)
      : nil

    let sample = SpeedSample(
      speedMps: clamped,
      latitude: loc.coordinate.latitude,
      longitude: loc.coordinate.longitude,
      timestampMs: loc.timestamp.timeIntervalSince1970 * 1000,
      accuracyM: loc.horizontalAccuracy
    )

    os_unfair_lock_lock(&lock)
    latest = sample
    samples.append(sample)
    os_unfair_lock_unlock(&lock)
  }

  /// The compositor's read. Returns `nil` when the newest fix is too old to
  /// describe the frame being drawn.
  func currentSpeedMps(now: Date = Date()) -> Double? {
    os_unfair_lock_lock(&lock)
    defer { os_unfair_lock_unlock(&lock) }
    guard let s = latest else { return nil }
    let ageSec = now.timeIntervalSince1970 - s.timestampMs / 1000
    guard ageSec <= SpeedStyle.maxFixAge else { return nil }
    return s.speedMps
  }
}
```

### Android — `LocationTracker.kt`

Same shape. `FusedLocationProviderClient` needs
`com.google.android.gms:play-services-location` added to the module's
`build.gradle`; the snapshot is a `@Volatile var` rather than a lock, since it
is a single reference assignment.

```kotlin
private val request = LocationRequest.Builder(
    Priority.PRIORITY_HIGH_ACCURACY,
    SpeedStyle.FIX_INTERVAL_MS,
  )
  // Without this the provider batches fixes to save power and the burned-in
  // speed lags the picture by seconds.
  .setMinUpdateIntervalMillis(SpeedStyle.FIX_INTERVAL_MS)
  .setWaitForAccurateLocation(true)
  .build()

override fun onLocationResult(result: LocationResult) {
  val loc = result.lastLocation ?: return

  // hasSpeed() is false on network-derived fixes. hasSpeedAccuracy() is API 26+
  // and minSdkVersion is already 26, so it is unconditionally available.
  val valid = loc.hasSpeed() && loc.hasSpeedAccuracy() &&
    loc.speedAccuracyMetersPerSecond <= SpeedStyle.MAX_SPEED_ERROR_MPS
  val clamped = if (!valid) null
    else if (loc.speed < SpeedStyle.STANDSTILL_MPS) 0f
    else loc.speed

  val sample = SpeedSample(clamped, loc.latitude, loc.longitude, loc.time, loc.accuracy)
  latest = sample                    // @Volatile — the compositor's read
  synchronized(samples) { samples += sample }
}
```

### `SpeedStyle` — the shared constants

A third mirrored pair beside `WatermarkStyle`, for the same reason: two
platforms must not disagree about when a car is stopped.

| Constant | Value | Why |
| --- | --- | --- |
| `standstillMps` | `0.6` | Below this, report 0. Roughly the ceiling of parked Doppler jitter; also below walking pace, so it can never suppress a real reading. |
| `maxFixAge` | `3.0 s` | Older than this and the frame gets `--`. Three fix intervals — enough to ride out one dropped update, short enough that a tunnel shows as unknown almost immediately. |
| `fixIntervalMs` | `1000` | One fix per second. §6 budgets 1 fix / 2–3 s for the *sidecar*, but the watermark is on screen continuously; 1 Hz is the slowest rate at which a burned-in speed doesn't visibly lag the road. |
| `maxSpeedErrorMps` | `2.0` | Reject fixes whose own error estimate exceeds ~7 km/h. |

---

## Part 2 — The watermark

The stamp today is `dd/MM/yyyy HH:mm:ss`. Speed joins it on the same plate,
after the clock:

```
05/08/2026 14:32:07   72 km/h
```

Three constraints the drawing code has to respect, all of them consequences of
`WatermarkStyle`'s existing comment about a fixed pattern and a jittering plate:

1. **The speed field is fixed-width.** Right-align into a three-character slot
   and reserve it even when the value is `--`. A plate that grows by a glyph
   between 99 and 100 km/h would visibly twitch every time.
2. **The unit is drawn, not implied.** `km/h` or `mph` per the setting, always
   present. A bare number burned into evidence is ambiguous across a border.
3. **The compositor never blocks.** `currentSpeedMps()` is a lock-and-copy of
   one struct. It must not allocate, format, or touch the location client.

Rendering is a one-line addition to the string each renderer already builds — no
change to plate geometry, which is derived from the text extent.

### The unit setting

`RecorderConfig` gains:

```ts
/** Unit for the burned-in speed. The sidecar is always SI regardless. */
speedUnit: 'kmh' | 'mph';
```

It threads through exactly where `cameraMode` threads: `config.ts` defaults,
`RecorderTypes.kt` / `RecorderTypes.swift` `@Field`, `ConfigStore` persistence,
and a `SettingRow` on the Settings screen. Unlike `cameraMode` it does **not**
require a session rebuild — it changes only what the next frame draws, so it
applies immediately and its row stays enabled while recording.

---

## Part 3 — The sidecar

`writeMetadataSidecar` stops being a no-op. It drains `LocationTracker.samples`
for the window being saved, filters to the merged clips' time range, and writes
JSON beside the MP4 at the path `StorageManager.metadataFileFor` already returns.

```json
{
  "version": 1,
  "clipId": "2026-08-05T14-32-07",
  "startedAtMs": 1786012327000,
  "durationSec": 180.4,
  "speedUnit": "mps",
  "samples": [
    { "t": 1786012327000, "lat": 41.3275, "lon": 19.8187, "speed": 20.1, "acc": 4.2 }
  ]
}
```

Notes that matter:

- **Always SI in the file.** `speed` is metres per second whatever the watermark
  displays. A sidecar is data; the display unit is a preference, and baking a
  preference into stored evidence means a file that can be misread later.
- **`speed` is `null`, never omitted, when unknown.** A gap in the array and a
  known-unknown sample are different facts about the drive.
- **Filter by the clips' own time range.** `Clip.startedAt` /
  `Clip.startedAtMs` already exist on both platforms; samples outside the merged
  window belong to footage that was evicted from the ring and must not appear.
- **Write it before the `fileExists` check.** Both `ClipMerger`s deliberately
  only advertise a sidecar that is on disk. That check becomes meaningful for
  the first time here — leave it exactly as it is.
- **Bound the sample log.** `LocationTracker` prunes samples older than
  `bufferDurationSec` on every append. At 1 Hz and a 15-minute buffer that is
  900 samples, a few tens of kilobytes — but it must be bounded, because the
  recorder is expected to run for hours.

---

## Part 4 — Permissions and platform configuration

### `app.json`

```jsonc
"ios": {
  "infoPlist": {
    "NSLocationWhenInUseUsageDescription":
      "DashCam stamps your speed and position onto recorded footage so a saved clip shows where and how fast you were driving.",
    "UIBackgroundModes": ["audio", "location"]
  }
},
"android": {
  "permissions": [
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.FOREGROUND_SERVICE_LOCATION"
  ]
}
```

- **`location` in `UIBackgroundModes`** is required because the recorder keeps
  running with the screen off. Without it iOS stops delivering fixes on
  backgrounding and every frame from then on stamps `--`.
- **`FOREGROUND_SERVICE_LOCATION`** must be paired with `location` added to the
  existing foreground service's `foregroundServiceType` in the manifest —
  Android 14+ throws `SecurityException` at service start otherwise. The service
  already declares `camera|microphone`; this is one more value in that list.
- **`ACCESS_COARSE_LOCATION` alongside fine.** Android 12+ lets the user grant
  only coarse. Coarse fixes have no usable speed, so the tracker must detect the
  downgrade and report `--` for the whole session rather than burning in
  nonsense from network positioning.
- **No background-location permission.** `ACCESS_BACKGROUND_LOCATION` triggers a
  Play Store policy review and is not needed: a foreground service with the
  location type covers screen-off recording.

### Store disclosure

`app.json` currently declares `"NSPrivacyCollectedDataTypes": []` and
`"NSPrivacyTracking": false`. Precise location written into a file on the
device is *collected* under Apple's definition even though nothing leaves the
phone, so the privacy manifest gains a
`NSPrivacyCollectedDataTypePreciseLocation` entry with linked-to-user `false`
and tracking `false`. Play's Data Safety form needs the matching declaration.
Both `docs/privacy-policy.md` and `docs/store-submission.md` need updating in
the same change — the policy currently makes no mention of location.

---

## Part 5 — Degradation, and the fallback that isn't the primary

Everything above assumes `speed` arrives on the fix. It nearly always does on
real hardware under open sky. Where it doesn't:

| Situation | Behaviour |
| --- | --- |
| No fix yet (cold start, 5–30 s) | `--`. Do not show 0 — the car may be moving. |
| Tunnel / underground | Fix goes stale, `--` after `maxFixAge`. |
| Coarse-only permission (Android 12+) | `--` for the session; Settings shows why. |
| Permission denied entirely | Speed omitted from the plate; the clock stays. |
| iOS Simulator | Never populates `speed`. Expected; not a bug to chase. |

**Position-differencing is a last-resort fallback only.** If `speed` is missing
across several consecutive *otherwise-valid* fixes — a small number of older
Android devices — deriving `haversine(p₁, p₂) / Δt` is better than a permanently
blank field. It goes behind a feature flag set by the tracker after observing
the failure, it needs a 3-sample moving average to be readable at all, and the
watermark should mark it (a trailing `~`) so footage produced this way is not
mistaken for a measured reading. **Build this after the primary path works and
only if a target device actually needs it.** Writing it first produces a worse
number that then hides the fact that the good path was never exercised.

---

## Verification

Ordered so that each step's failure is unambiguous.

1. **Unit-test the validation and clamping.** `speed = -1` → `null`. Negative
   `speedAccuracy` → `null`. `0.4 m/s` → `0`. `0.8 m/s` → `0.8`. A sample
   `4 s` old → `currentSpeedMps` returns `null`. No device needed; this is where
   the standstill and staleness rules get pinned down.

2. **Simulated motion, both platforms.** iOS: Xcode → Debug → Simulate Location
   → *Freeway Drive*, which produces a genuine speed track. Android: emulator
   extended controls → Location → import a GPX and play it back. Run
   `pnpm ios` / `pnpm android`, watch the stamp on the preview. Expect a number
   that rises and falls smoothly, not one that jumps by tens between frames.

3. **Stationary hold.** Leave the device still with a real sky view for 60 s.
   The stamp must read `0` continuously — any flicker to a non-zero value means
   `standstillMps` is too low for that receiver.

4. **A real drive, then inspect the artefact.** Record, Save, and open the saved
   MP4. The burned-in speed is the actual check: compare against the car's
   speedometer, which reads 3–7 % *optimistic* by design in the EU, so GPS
   reading slightly low is correct behaviour, not a bug.

5. **Read the sidecar.** `cat` the `metadataUri` file from the saved clip. Check
   it parses, that sample timestamps span the clip's duration and no more, that
   speeds are in m/s, and that unknown samples are `null` rather than absent.

6. **Tunnel / airplane-mode test.** Toggle location off mid-recording. The stamp
   must fall to `--` within ~3 s and recover when re-enabled — not freeze on the
   last value.

7. **Screen-off.** Start recording, lock the phone for two minutes, save. The
   footage from the locked period must carry live speed. This is the step that
   catches a missing `UIBackgroundModes` entry or foreground-service type, and
   it fails silently in every earlier step.
