package expo.modules.loopcamrecorder

import android.content.Context

/**
 * Where the settings live between launches.
 *
 * Natively rather than in JS, for two reasons. The config is already owned by
 * the native controller, and [RecordingService] runs in a process that can
 * outlive the JS side (§5.1) — a JS-side store would leave the service booting
 * on the wrong camera after a restart, before any JavaScript had run to correct
 * it.
 *
 * Fields are stored individually, not as one serialised blob: a config written
 * by a newer build then read by an older one gains the fields it knows and
 * ignores the rest, instead of failing to decode and silently resetting
 * everything the user chose.
 */
internal class ConfigStore(context: Context) {

  private val prefs = context.getSharedPreferences("loopcam.config", Context.MODE_PRIVATE)

  fun load(): RecorderConfig {
    val defaults = RecorderConfig()
    return RecorderConfig().apply {
      clipDurationSec = prefs.getFloat(CLIP_DURATION, defaults.clipDurationSec.toFloat()).toDouble()
      bufferDurationSec =
        prefs.getFloat(BUFFER_DURATION, defaults.bufferDurationSec.toFloat()).toDouble()
      // Round-tripped through the enums, so a tier or a mode this build does not
      // know — written by a newer one, then downgraded — lands on the default
      // rather than being handed to CameraX as a string it cannot resolve.
      quality = VideoQuality.from(prefs.getString(QUALITY, defaults.quality)!!).jsValue
      cameraMode = CameraMode.from(prefs.getString(CAMERA_MODE, defaults.cameraMode)!!).jsValue
      audioEnabled = prefs.getBoolean(AUDIO, defaults.audioEnabled)
      locationTaggingEnabled = prefs.getBoolean(LOCATION, defaults.locationTaggingEnabled)
      speedUnit = SpeedUnit.from(prefs.getString(SPEED_UNIT, defaults.speedUnit)!!).jsValue
      impactDetectionEnabled = prefs.getBoolean(IMPACT, defaults.impactDetectionEnabled)
      autoStopBatteryPercent = prefs.getInt(BATTERY, defaults.autoStopBatteryPercent)
    }
  }

  fun save(config: RecorderConfig) {
    prefs.edit()
      .putFloat(CLIP_DURATION, config.clipDurationSec.toFloat())
      .putFloat(BUFFER_DURATION, config.bufferDurationSec.toFloat())
      .putString(QUALITY, config.quality)
      .putString(CAMERA_MODE, config.cameraMode)
      .putBoolean(AUDIO, config.audioEnabled)
      .putBoolean(LOCATION, config.locationTaggingEnabled)
      .putString(SPEED_UNIT, config.speedUnit)
      .putBoolean(IMPACT, config.impactDetectionEnabled)
      .putInt(BATTERY, config.autoStopBatteryPercent)
      .apply()
  }

  private companion object {
    const val CLIP_DURATION = "clipDurationSec"
    const val BUFFER_DURATION = "bufferDurationSec"
    const val QUALITY = "quality"
    const val CAMERA_MODE = "cameraMode"
    const val AUDIO = "audioEnabled"
    const val LOCATION = "locationTaggingEnabled"
    const val SPEED_UNIT = "speedUnit"
    const val IMPACT = "impactDetectionEnabled"
    const val BATTERY = "autoStopBatteryPercent"
  }
}
