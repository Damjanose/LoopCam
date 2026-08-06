package expo.modules.loopcamrecorder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

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

    val sidecar = storage.metadataFileFor(destination)
    return SavedClip(
      // The filename is the identity (§7.1) — the saved directory is the index,
      // so this must match what listSavedClips/deleteSavedClip look up.
      id = destination.nameWithoutExtension,
      uri = destination.toURI().toString(),
      // Only advertise a sidecar that is actually on disk. The writer swallows
      // its own failures rather than losing the merged clip over them, so this
      // check is the one thing standing between a JSON file that never got
      // written and a URI pointing at nothing.
      metadataUri = if (sidecar.exists()) sidecar.toURI().toString() else null,
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
   * The track layout is taken from the first clip and reused for all of them:
   * every segment left the same [android.media.MediaCodec] instance with the
   * same format, so re-deriving it per clip would only be a chance to disagree.
   * Sample timestamps get a running offset so they stay monotonic across joins —
   * without it a player sees time jump back to zero at every seam and stalls.
   */
  private fun concatenate(clips: List<Clip>, destination: File) {
    val sources = clips.map { it.file }.filter { it.isFile && it.length() > 0L }
    check(sources.isNotEmpty()) { "None of the buffered clips are readable" }

    destination.parentFile?.mkdirs()
    val muxer = MediaMuxer(destination.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var started = false
    try {
      val layout = readLayout(sources.first(), muxer)
      // Must precede start(): the rotation lives in the container header, and
      // without it every saved clip plays back sideways.
      rotationOf(sources.first())?.let(muxer::setOrientationHint)

      muxer.start()
      started = true

      var offsetUs = 0L
      for (source in sources) {
        offsetUs = copySamples(source, muxer, layout, offsetUs)
      }

      muxer.stop()
      started = false
    } catch (t: Throwable) {
      destination.delete()
      throw t
    } finally {
      if (started) runCatching { muxer.stop() }
      runCatching { muxer.release() }
    }

    // Outside the catch above, so this needs its own cleanup: a zero-byte file
    // left behind is indistinguishable from a real incident to the saved-clips
    // list (the directory *is* the index, §7.1) and can never be played.
    if (destination.length() == 0L) {
      destination.delete()
      throw IllegalStateException("The merged file came out empty")
    }
  }

  /** Copy the first clip's track formats into the muxer; that is the layout. */
  private fun readLayout(source: File, muxer: MediaMuxer): TrackLayout {
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(source.absolutePath)
      var video: Int? = null
      var audio: Int? = null
      var bufferBytes = MIN_BUFFER_BYTES
      for (track in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
        bufferBytes = maxOf(bufferBytes, format.maxInputSizeOrZero())
        when {
          mime.startsWith("video/") && video == null -> video = muxer.addTrack(format)
          mime.startsWith("audio/") && audio == null -> audio = muxer.addTrack(format)
        }
      }
      checkNotNull(video) { "The first buffered clip has no video track" }
      return TrackLayout(video, audio, bufferBytes)
    } finally {
      extractor.release()
    }
  }

  /**
   * Copy one clip's samples into [muxer], shifted by [offsetUs]. Returns the
   * offset the next clip should start at.
   */
  private fun copySamples(
    source: File,
    muxer: MediaMuxer,
    layout: TrackLayout,
    offsetUs: Long,
  ): Long {
    val extractor = MediaExtractor()
    val buffer = ByteBuffer.allocateDirect(layout.bufferBytes)
    val info = MediaCodec.BufferInfo()
    var lastPtsUs = offsetUs
    try {
      extractor.setDataSource(source.absolutePath)

      // Route by media type rather than by index: a clip whose tracks come back
      // in a different order still lands in the right output track.
      val routes = HashMap<Int, Int>()
      for (track in 0 until extractor.trackCount) {
        val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) ?: continue
        val target = when {
          mime.startsWith("video/") -> layout.videoTrack
          mime.startsWith("audio/") -> layout.audioTrack
          else -> null
        } ?: continue
        routes[track] = target
        extractor.selectTrack(track)
      }
      if (routes.isEmpty()) {
        Log.w(TAG, "Skipping ${source.name}: no track matches the merged layout")
        return offsetUs
      }

      while (true) {
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) break
        val target = routes[extractor.sampleTrackIndex]
        if (target != null) {
          val ptsUs = offsetUs + extractor.sampleTime
          buffer.position(0)
          buffer.limit(size)
          info.offset = 0
          info.size = size
          info.presentationTimeUs = ptsUs
          // Only the sync bit is safe to forward — MediaExtractor's other sample
          // flags do not share MediaCodec's bit positions.
          info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            MediaCodec.BUFFER_FLAG_KEY_FRAME
          } else {
            0
          }
          muxer.writeSampleData(target, buffer, info)
          if (ptsUs > lastPtsUs) lastPtsUs = ptsUs
        }
        extractor.advance()
      }
    } finally {
      extractor.release()
    }
    // Nudge past the last sample so the next clip's first frame is strictly
    // later; identical timestamps at a seam make some players hang.
    return lastPtsUs + SEAM_GAP_US
  }

  /**
   * CameraX records in the sensor's orientation and stores the correction in the
   * container, so the merge has to carry it over by hand.
   */
  private fun rotationOf(source: File): Int? {
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(source.absolutePath)
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
    } catch (t: Throwable) {
      Log.w(TAG, "Could not read rotation from ${source.name}", t)
      null
    } finally {
      runCatching { retriever.release() }
    }
  }

  /**
   * §7.1 — GPS/speed/timestamp sidecar written next to the saved clip.
   *
   * Mirrored byte for byte by `ios/ClipMerger.swift`: a sidecar that described
   * the same drive differently depending on the phone it was recorded on would
   * be worth very little as evidence.
   *
   * Failures are swallowed rather than propagated. The merged MP4 is already on
   * disk and is the thing the user pressed Save for; losing it because a
   * few kilobytes of JSON would not write would be the wrong trade. The
   * `fileExists` check in [merge] is what keeps a failure here from being
   * advertised as a sidecar that isn't there.
   */
  fun writeMetadataSidecar(destination: File, clips: List<Clip>) {
    try {
      // The merged window's own time range. Samples outside it belong to
      // footage that was evicted from the ring and must not appear — a sidecar
      // is a description of *this* file, not of the drive around it.
      val first = clips.first()
      val last = clips.last()
      val startedAtMs = first.startedAtMs
      val endedAtMs = last.startedAtMs + (last.durationSec * 1000).toLong()

      val samples = JSONArray()
      for (sample in LocationTracker.samplesBetween(startedAtMs, endedAtMs)) {
        samples.put(
          JSONObject().apply {
            put("t", sample.timestampMs)
            put("lat", sample.latitude)
            put("lon", sample.longitude)
            // JSONObject.NULL, not omission: a gap in the array and a
            // known-unknown sample are different facts about the drive.
            put("speed", sample.speedMps ?: JSONObject.NULL)
            put("acc", sample.accuracyM)
            if (sample.derived) put("derived", true)
          }
        )
      }

      val document = JSONObject().apply {
        put("version", SIDECAR_VERSION)
        put("clipId", destination.nameWithoutExtension)
        put("startedAtMs", startedAtMs)
        put("durationSec", clips.sumOf { it.durationSec })
        // Always SI in the file, whatever the watermark displays. A sidecar is
        // data; the display unit is a preference, and baking a preference into
        // stored evidence means a file that can be misread later.
        put("speedUnit", "mps")
        put("samples", samples)
      }

      storage.metadataFileFor(destination).writeText(document.toString())
    } catch (t: Throwable) {
      Log.w(TAG, "Could not write the metadata sidecar for ${destination.name}", t)
    }
  }

  /** Output track indices plus the read buffer they need. */
  private class TrackLayout(
    val videoTrack: Int,
    val audioTrack: Int?,
    val bufferBytes: Int,
  )

  private companion object {
    const val TAG = "LoopCam/Merge"

    /**
     * Sidecar schema version. Bumped when a field changes meaning, so a reader
     * handed a file from a future build can tell rather than guess.
     */
    const val SIDECAR_VERSION = 1

    /** Floor for the sample buffer when a format under-reports its input size. */
    const val MIN_BUFFER_BYTES = 2 * 1024 * 1024

    /** ~1 ms — enough to keep timestamps strictly increasing across a seam. */
    const val SEAM_GAP_US = 1_000L

    fun MediaFormat.maxInputSizeOrZero(): Int =
      if (containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
  }
}
