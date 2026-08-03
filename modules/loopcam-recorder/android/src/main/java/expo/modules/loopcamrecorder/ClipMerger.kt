package expo.modules.loopcamrecorder

import java.io.File
import java.util.UUID

/**
 * §4 — Save concatenates the frozen window into one permanent file.
 *
 * Deliberately MediaMuxer-based rather than FFmpeg: every clip comes out of the
 * same encoder with identical format, so the merge is a stream copy (no
 * re-encode), which is both fast and the cheapest possible use of the CPU while
 * the live recording thread keeps running (§6).
 */
class ClipMerger(private val storage: StorageManager) {

  /** Merge [clips] into [destination] and write the metadata sidecar. */
  fun merge(
    clips: List<Clip>,
    destination: File,
    config: RecorderConfig,
    trigger: SaveTrigger,
  ): SavedClip {
    require(clips.isNotEmpty()) { "Nothing to merge" }

    concatenate(clips, destination)
    if (config.locationTaggingEnabled) {
      writeMetadataSidecar(destination, clips)
    }

    return SavedClip(
      id = UUID.randomUUID().toString(),
      uri = destination.toURI().toString(),
      metadataUri = if (config.locationTaggingEnabled) {
        storage.metadataFileFor(destination).toURI().toString()
      } else {
        null
      },
      createdAtMs = System.currentTimeMillis(),
      durationSec = clips.sumOf { it.durationSec },
      sizeBytes = destination.sizeOrZero(),
      isProtected = false,
      trigger = trigger,
    )
  }

  /**
   * Stream-copy every clip into one MP4.
   *
   * TODO(phase-1): implement with MediaExtractor + MediaMuxer —
   *   1. open the first clip, copy its video/audio track formats into the muxer;
   *   2. for each clip, read sample buffers and write them with a running
   *      presentation-time offset so timestamps stay monotonic across joins;
   *   3. fall back to FFmpeg-kit only if a device ever produces mismatched
   *      formats mid-session (rotation change, resolution fallback).
   * Throwing until then, so nothing silently reports a successful save.
   */
  private fun concatenate(clips: List<Clip>, destination: File) {
    throw NotImplementedError("ClipMerger.concatenate is not implemented yet (Phase 1)")
  }

  /**
   * §7.1 — GPS/speed/timestamp sidecar written next to the saved clip.
   *
   * TODO(phase-5): serialize the location samples collected during the window
   * (FusedLocationProviderClient at 1 fix / 2–3 s, §6) as JSON.
   */
  fun writeMetadataSidecar(destination: File, clips: List<Clip>) {
    // TODO(phase-5)
  }
}
