package expo.modules.loopcamrecorder

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * What this device's cameras can actually do.
 *
 * Settings renders from this instead of offering every mode and finding out at
 * Play that the hardware refuses. On a dash mount a toast is never read, and a
 * recorder that quietly records something other than what was asked for is
 * worse than one that never offered.
 *
 * The probe runs once, on a background thread started at module creation, and
 * the answer is cached for the process's life — it is a property of the
 * hardware and cannot change while the app is running. [capabilities] waits for
 * it, which in practice returns immediately: CameraX is initialised long before
 * the first render that asks.
 */
internal class CameraProbe(private val context: Context) {

  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "loopcam-probe").apply { isDaemon = true }
  }

  private val pending: Future<Map<String, Any>> = executor.submit<Map<String, Any>> { probe() }

  /**
   * Blocks, but bounded: a probe that has not finished within the timeout is a
   * CameraX that is not coming up, and the honest answer then is the
   * conservative one rather than a frozen settings screen.
   */
  fun capabilities(): Map<String, Any> = try {
    pending.get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
  } catch (t: Throwable) {
    Log.w(TAG, "Camera probe did not finish; reporting back-only", t)
    fallback()
  }

  private fun probe(): Map<String, Any> {
    val provider = runCatching {
      ProcessCameraProvider.getInstance(context).get(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
    }.getOrElse {
      Log.w(TAG, "No camera provider; reporting back-only", it)
      return fallback()
    }

    val back = runCatching { provider.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrNull()
    val front =
      runCatching { provider.getCameraInfo(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrNull()

    val backTiers = tiersFor(back)
    val frontTiers = tiersFor(front)

    // A concurrent combination is only useful to us if it pairs a front camera
    // with a back one — some devices report pairs of back cameras, which is a
    // different feature entirely.
    val dualSupported = runCatching {
      provider.availableConcurrentCameraInfos.any { combination ->
        combination.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
          combination.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
      }
    }.getOrDefault(false)

    // Concurrent mode constrains both streams; offering 1080p there would be a
    // promise the bind cannot keep.
    val dualTiers = if (dualSupported) backTiers.filter { it in DUAL_TIERS } else emptyList()

    val modes = buildList {
      add(CameraMode.BACK.jsValue)
      if (frontTiers.isNotEmpty()) add(CameraMode.FRONT.jsValue)
      if (dualTiers.isNotEmpty()) add(CameraMode.BOTH.jsValue)
    }

    return mapOf(
      "modes" to modes,
      "qualities" to mapOf(
        CameraMode.BACK.jsValue to backTiers,
        CameraMode.FRONT.jsValue to frontTiers,
        CameraMode.BOTH.jsValue to dualTiers,
      ),
    )
  }

  /**
   * The tiers this camera can actually record, cheapest first.
   *
   * Asked of the recorder rather than assumed: a phone that cannot do 4K should
   * not be offered it, and one that cannot do 720p should not have the option
   * silently resolve to something else. `SD` covers both of our lowest tiers —
   * CameraX has nothing between 176x144 and 480p worth binding to.
   */
  private fun tiersFor(info: CameraInfo?): List<String> {
    if (info == null) return emptyList()
    // SDR explicitly: it is what the recorder is built for, and asking about a
    // dynamic range the session will never request would report tiers that are
    // only reachable in HDR.
    val supported = runCatching {
      Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
    }.getOrElse {
      Log.w(TAG, "Could not read supported qualities; assuming the full ladder", it)
      return VideoQuality.entries.map { quality -> quality.jsValue }
    }
    if (supported.isEmpty()) return emptyList()

    return VideoQuality.entries
      .filter { tier ->
        when (tier) {
          VideoQuality.SD_360, VideoQuality.SD_480 -> Quality.SD in supported
          VideoQuality.HD_720 -> Quality.HD in supported
          VideoQuality.HD_1080 -> Quality.FHD in supported
          VideoQuality.UHD_4K -> Quality.UHD in supported
        }
      }
      .map { it.jsValue }
  }

  /** Back-only, full ladder: never claims a mode, never blocks recording. */
  private fun fallback(): Map<String, Any> = mapOf(
    "modes" to listOf(CameraMode.BACK.jsValue),
    "qualities" to mapOf(
      CameraMode.BACK.jsValue to VideoQuality.entries.map { it.jsValue },
      CameraMode.FRONT.jsValue to emptyList<String>(),
      CameraMode.BOTH.jsValue to emptyList<String>(),
    ),
  )

  private companion object {
    const val TAG = "LoopCam/Probe"
    const val PROBE_TIMEOUT_SEC = 5L

    /** The ceiling concurrent mode can hold on the devices that support it. */
    val DUAL_TIERS = listOf(
      VideoQuality.SD_360.jsValue,
      VideoQuality.SD_480.jsValue,
      VideoQuality.HD_720.jsValue,
    )
  }
}
