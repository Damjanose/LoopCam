package expo.modules.loopcamrecorder

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlin.math.ceil

/** §2.3 state machine. Mirrors `RecorderState` in LoopcamRecorder.types.ts. */
enum class RecorderState(val jsValue: String) {
  IDLE("idle"),
  RECORDING("recording"),
  SAVING("saving"),
  STOPPING("stopping"),
}

enum class VideoQuality(val jsValue: String, val bitrateBps: Int) {
  /**
   * CameraX's lowest *named* tier is SD (480p), so this records at 480p. The
   * alternative, `Quality.LOWEST`, resolves to whatever the device calls lowest
   * — 176x144 on some hardware, which is worthless as evidence. Settings says
   * so out loud rather than letting the buffer fill with a surprise.
   */
  SD_360("360p", 800_000),
  SD_480("480p", 1_200_000),
  HD_720("720p", 2_500_000),
  HD_1080("1080p", 5_000_000),
  UHD_4K("4k", 20_000_000);

  companion object {
    fun from(value: String): VideoQuality =
      entries.firstOrNull { it.jsValue == value } ?: HD_1080
  }
}

/**
 * Which camera(s) feed the buffer. Mirrors `CameraMode` on the JS side.
 *
 * [BOTH] is a composite, not two recordings: the front camera is drawn into the
 * top-right of the back camera's frame before it reaches the encoder, so one
 * file comes out and every type downstream — the ring buffer, the merge, the
 * gallery — is untouched by the choice.
 */
enum class CameraMode(val jsValue: String) {
  BACK("back"),
  FRONT("front"),
  BOTH("both");

  companion object {
    /** Anything unrecognised — a value written by a newer build — falls back. */
    fun from(value: String): CameraMode =
      entries.firstOrNull { it.jsValue == value } ?: BACK
  }
}

/** §2.1 — the two independent settings, plus the v1 feature toggles. */
class RecorderConfig : Record {
  @Field var clipDurationSec: Double = 10.0
  @Field var bufferDurationSec: Double = 120.0
  @Field var quality: String = "1080p"
  @Field var cameraMode: String = "back"
  @Field var audioEnabled: Boolean = true
  @Field var locationTaggingEnabled: Boolean = true
  @Field var impactDetectionEnabled: Boolean = true
  @Field var autoStopBatteryPercent: Int = 15

  /** §2.2 — max_clips = ceil(buffer_duration / clip_duration). */
  val maxClips: Int
    get() = ceil(bufferDurationSec / clipDurationSec).toInt().coerceAtLeast(1)

  val videoQuality: VideoQuality get() = VideoQuality.from(quality)

  val camera: CameraMode get() = CameraMode.from(cameraMode)

  fun toMap(): Map<String, Any> = mapOf(
    "clipDurationSec" to clipDurationSec,
    "bufferDurationSec" to bufferDurationSec,
    "quality" to quality,
    "cameraMode" to cameraMode,
    "audioEnabled" to audioEnabled,
    "locationTaggingEnabled" to locationTaggingEnabled,
    "impactDetectionEnabled" to impactDetectionEnabled,
    "autoStopBatteryPercent" to autoStopBatteryPercent,
  )
}

/** Snapshot of the ring buffer, emitted on every clip boundary. */
data class BufferStatus(
  val state: RecorderState,
  val clipCount: Int,
  val maxClips: Int,
  val bufferedSec: Double,
  val bufferedBytes: Long,
  val elapsedSec: Double,
) {
  fun toMap(): Map<String, Any> = mapOf(
    "state" to state.jsValue,
    "clipCount" to clipCount,
    "maxClips" to maxClips,
    "bufferedSec" to bufferedSec,
    "bufferedBytes" to bufferedBytes,
    "elapsedSec" to elapsedSec,
  )
}

enum class SaveTrigger(val jsValue: String) {
  MANUAL("manual"),
  IMPACT("impact"),
  LOW_BATTERY("lowBattery"),
}

data class SavedClip(
  val id: String,
  val uri: String,
  val metadataUri: String?,
  val createdAtMs: Long,
  val durationSec: Double,
  val sizeBytes: Long,
  val isProtected: Boolean,
  val trigger: SaveTrigger,
) {
  fun toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "uri" to uri,
    "metadataUri" to metadataUri,
    "createdAtMs" to createdAtMs,
    "durationSec" to durationSec,
    "sizeBytes" to sizeBytes,
    "protected" to isProtected,
    "trigger" to trigger.jsValue,
  )
}

data class StorageStatus(
  val freeBytes: Long,
  val savedBytes: Long,
  val savedClipCount: Int,
  val lowSpaceWarning: Boolean,
) {
  fun toMap(): Map<String, Any> = mapOf(
    "freeBytes" to freeBytes,
    "savedBytes" to savedBytes,
    "savedClipCount" to savedClipCount,
    "lowSpaceWarning" to lowSpaceWarning,
  )
}

/** Error codes shared with `RecorderErrorCode` on the JS side. */
enum class RecorderErrorCode(val jsValue: String) {
  PERMISSION_DENIED("permissionDenied"),
  CAMERA_UNAVAILABLE("cameraUnavailable"),
  STORAGE_FULL("storageFull"),
  MERGE_FAILED("mergeFailed"),
  SERVICE_KILLED("serviceKilled"),
  UNKNOWN("unknown"),
}
