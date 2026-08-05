package expo.modules.loopcamrecorder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** A speed reading at an instant. A null [speedMps] means "not currently known". */
internal data class SpeedSample(
  /** Metres per second, already validated and clamped. Null when unknown. */
  val speedMps: Double?,
  val latitude: Double,
  val longitude: Double,
  /** Fix time, epoch ms — the receiver's clock, not when we processed it. */
  val timestampMs: Long,
  /** Horizontal accuracy in metres, for the sidecar's benefit. */
  val accuracyM: Double,
  /**
   * True when [speedMps] was inferred from two positions rather than measured
   * (§Part 5). Carried all the way into the sidecar and the watermark so an
   * inference can never be mistaken for a measurement.
   */
  val derived: Boolean = false,
)

/** Why the burned-in speed is reading `--`. Mirrors `LocationStatus` in JS. */
internal enum class LocationStatus(val jsValue: String) {
  OK("ok"),
  NO_FIX("noFix"),
  COARSE_ONLY("coarseOnly"),
  DENIED("denied"),
  DISABLED("disabled"),
}

/**
 * The location client, and the only place a speed comes from.
 *
 * Speed is read from the fix, never computed from two positions. Both platforms
 * derive the `speed` field from the Doppler shift on the GNSS carrier — a direct
 * measurement of velocity along the line of sight to each satellite, typically
 * accurate to under 0.5 m/s even while the *position* is drifting several
 * metres. Differencing two positions inherits that drift and then divides it by
 * the sample interval: ±5 m of ordinary urban position error across a 1 s gap
 * reads as ±18 km/h of speed that isn't there. For a number that gets burned
 * irreversibly into evidence, that difference is the whole argument.
 *
 * A singleton rather than an injected dependency because its two consumers sit
 * in unrelated object graphs — [WatermarkOverlay] is built inside the capture
 * session, [ClipMerger] up in the module — and threading one reference through
 * four constructors to reach both would be noise. It holds the application
 * context only.
 *
 * Deliberately not routed through JS. The watermark needs this value at 30 fps
 * and the sidecar needs it during a merge; a round trip across the bridge would
 * make the burned-in speed depend on the JS thread being responsive, which
 * during a merge it may not be.
 */
@SuppressLint("StaticFieldLeak") // Application context only — see [attach].
internal object LocationTracker {

  private const val TAG = "LoopCam/Location"

  /**
   * How far past the buffer window samples are kept.
   *
   * The sidecar covers the merged clips' own time range, and the oldest clip in
   * a full ring starts one whole window ago; a margin means a sample landing
   * fractionally before that boundary is still there to describe it.
   */
  private const val RETENTION_MARGIN_MS = 60_000L

  private var appContext: Context? = null
  private var client: FusedLocationProviderClient? = null

  /**
   * The compositor's read. Volatile rather than locked: it is a single
   * reference assignment, and the draw thread must never block on a frame.
   */
  @Volatile
  private var latest: SpeedSample? = null

  /** Everything seen since [start], for the sidecar. Bounded by [retentionMs]. */
  private val samples = ArrayList<SpeedSample>()

  @Volatile
  private var retentionMs = 180_000L

  @Volatile
  private var enabled = true

  @Volatile
  private var running = false

  @Volatile
  private var permission = LocationStatus.DENIED

  /**
   * §Part 5 — the fallback that isn't the primary.
   *
   * Set once, after enough consecutive otherwise-valid fixes have arrived with
   * no usable speed that the receiver is clearly not going to produce a Doppler
   * solution at all. Never unset within a session: a device that flickers
   * between measured and derived readings would put a `~` on and off the plate
   * every few seconds, which reads as a fault rather than as a caveat.
   */
  @Volatile
  private var derivedMode = false

  private var missingSpeedRun = 0

  /** The last few derived readings, averaged before any of them is shown. */
  private val derivedWindow = ArrayDeque<Double>()

  private val callback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
      result.lastLocation?.let(::onFix)
    }
  }

  /**
   * Hand the tracker the application context, once, at module creation.
   *
   * Not an Activity or a React context: the recorder outlives both — the
   * foreground service keeps running with the app swiped away (§5.1) — and a
   * tracker holding either would either leak it or stop delivering fixes at the
   * exact moment the footage still needs them.
   */
  fun attach(context: Context) {
    appContext = context.applicationContext
  }

  /** Start tracking for a recording session. Idempotent. */
  fun start(config: RecorderConfig) {
    configure(config)
    if (!config.locationTaggingEnabled) return
    if (running) return

    val context = appContext ?: run {
      Log.w(TAG, "Cannot start: no context attached")
      return
    }

    permission = permissionStatus(context)
    // Coarse fixes come from Wi-Fi and cell towers, and their `speed` is either
    // absent or garbage. Burning a network-derived number into footage is worse
    // than burning nothing, so a coarse-only grant does not start the client at
    // all — Settings explains the blank instead.
    if (permission != LocationStatus.OK) {
      Log.w(TAG, "Not starting: location permission is $permission")
      return
    }

    synchronized(samples) { samples.clear() }
    latest = null
    // A fresh session re-tests the primary path. Carrying derivedMode across
    // sessions would mean one bad drive under a bridge permanently downgraded
    // the app on a device that measures speed perfectly well.
    derivedMode = false
    missingSpeedRun = 0
    derivedWindow.clear()

    val request = LocationRequest.Builder(
      Priority.PRIORITY_HIGH_ACCURACY,
      SpeedStyle.FIX_INTERVAL_MS,
    )
      // Without this the provider batches fixes to save power and the burned-in
      // speed lags the picture by seconds.
      .setMinUpdateIntervalMillis(SpeedStyle.FIX_INTERVAL_MS)
      .setWaitForAccurateLocation(true)
      .build()

    val fused = LocationServices.getFusedLocationProviderClient(context)
    client = fused
    try {
      // Delivered on the main looper. The callback does no work beyond
      // validating and storing one struct, so it cannot hold the thread up.
      fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
      running = true
    } catch (e: SecurityException) {
      // Revoked between the check above and here — rare, but it must not take
      // the recording down with it.
      Log.w(TAG, "Location permission revoked while starting", e)
      permission = LocationStatus.DENIED
      client = null
    }
  }

  fun stop() {
    client?.removeLocationUpdates(callback)
    client = null
    running = false
    latest = null
    synchronized(samples) { samples.clear() }
  }

  /**
   * Apply a config change without restarting. Retention follows the buffer
   * window, and turning tagging off mid-drive stops the client immediately
   * rather than at the next Play.
   */
  fun configure(config: RecorderConfig) {
    retentionMs = (config.bufferDurationSec * 1000).toLong() + RETENTION_MARGIN_MS
    enabled = config.locationTaggingEnabled
    if (!enabled && running) stop()
  }

  /**
   * The compositor's read: the newest fix, or null when it is too old to
   * describe the frame being drawn.
   *
   * A read of one volatile reference plus an arithmetic comparison. It does not
   * allocate, format, or touch the location client — this runs 30 times a
   * second on the draw thread.
   */
  fun currentSpeed(nowMs: Long = System.currentTimeMillis()): SpeedSample? {
    val sample = latest ?: return null
    return if (SpeedStyle.isFresh(sample.timestampMs, nowMs)) sample else null
  }

  /**
   * The samples describing a merged window, for the sidecar.
   *
   * Non-destructive: a merge that fails must not have consumed the samples that
   * would describe a later retry. Bounding is [retentionMs]'s job instead.
   */
  fun samplesBetween(fromMs: Long, toMs: Long): List<SpeedSample> =
    synchronized(samples) { samples.filter { it.timestampMs in fromMs..toMs } }

  /** Why the speed is reading `--`, for Settings to explain. */
  fun status(nowMs: Long = System.currentTimeMillis()): LocationStatus = when {
    !enabled -> LocationStatus.DISABLED
    permission != LocationStatus.OK -> permission
    currentSpeed(nowMs)?.speedMps != null -> LocationStatus.OK
    else -> LocationStatus.NO_FIX
  }

  private fun onFix(location: Location) {
    // hasSpeed() is false on network-derived fixes; hasSpeedAccuracy() is API
    // 26+ and minSdkVersion is already 26, so it is unconditionally available.
    // Absent values are passed as -1, which SpeedStyle rejects — the same path
    // CoreLocation's own -1 takes on iOS.
    val speed = if (location.hasSpeed()) location.speed.toDouble() else -1.0
    val accuracy =
      if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else -1.0

    var measured = SpeedStyle.validate(speed, accuracy)
    var derived = false

    if (measured != null) {
      missingSpeedRun = 0
    } else if (++missingSpeedRun >= SpeedStyle.DERIVE_AFTER_MISSING_FIXES) {
      // The receiver is not going to hand us a Doppler solution. A number
      // inferred from two positions is worse, but it is better than a field
      // that is blank for the whole drive — and it is marked, so footage
      // produced this way cannot be mistaken for a measured reading.
      if (!derivedMode) {
        Log.i(TAG, "No usable Doppler speed after $missingSpeedRun fixes; deriving from position")
        derivedMode = true
      }
      derive(location)?.let {
        measured = it
        derived = true
      }
    }

    val sample = SpeedSample(
      speedMps = measured,
      latitude = location.latitude,
      longitude = location.longitude,
      timestampMs = location.time,
      accuracyM = location.accuracy.toDouble(),
      derived = derived,
    )

    latest = sample
    synchronized(samples) {
      samples += sample
      prune(sample.timestampMs)
    }
  }

  /**
   * §Part 5 — speed from `haversine(p₁, p₂) / Δt`, smoothed.
   *
   * Only ever reached in [derivedMode]. Returns null until the moving average
   * has enough readings to be worth showing: a single difference between two
   * drifting positions is not a speed, it is noise with a unit.
   *
   * Caller runs on the delivery thread, which is the only writer of
   * [derivedWindow] and [missingSpeedRun].
   */
  private fun derive(location: Location): Double? {
    val previous = latest ?: return null
    val deltaSec = (location.time - previous.timestampMs) / 1000.0
    // A zero or negative interval is a duplicate or an out-of-order fix, and
    // dividing by it produces an infinity that would burn straight into the
    // footage.
    if (deltaSec <= 0) return null

    val metres = SpeedStyle.haversineM(
      previous.latitude, previous.longitude, location.latitude, location.longitude
    )
    val raw = metres / deltaSec
    if (raw > SpeedStyle.MAX_DERIVED_MPS) return null

    derivedWindow.addLast(raw)
    while (derivedWindow.size > SpeedStyle.DERIVED_WINDOW) derivedWindow.removeFirst()
    if (derivedWindow.size < SpeedStyle.DERIVED_WINDOW) return null

    val mean = derivedWindow.average()
    // The same standstill clamp the measured path gets, and for the same
    // reason: position drift on a parked car is exactly what this computes.
    return if (mean < SpeedStyle.STANDSTILL_MPS) 0.0 else mean
  }

  /**
   * Drop samples older than the window they could ever describe.
   *
   * At 1 Hz and a 15-minute buffer this holds ~900 samples — a few tens of
   * kilobytes — but it has to be bounded, because the recorder is expected to
   * run for hours.
   *
   * Caller holds the [samples] lock.
   */
  private fun prune(nowMs: Long) {
    val cutoff = nowMs - retentionMs
    val keepFrom = samples.indexOfFirst { it.timestampMs >= cutoff }
    when (keepFrom) {
      -1 -> samples.clear()
      0 -> Unit
      else -> samples.subList(0, keepFrom).clear()
    }
  }

  private fun permissionStatus(context: Context): LocationStatus {
    val fine = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return when {
      fine -> LocationStatus.OK
      coarse -> LocationStatus.COARSE_ONLY
      else -> LocationStatus.DENIED
    }
  }

  private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
