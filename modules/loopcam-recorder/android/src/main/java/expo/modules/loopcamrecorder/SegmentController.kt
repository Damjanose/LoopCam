package expo.modules.loopcamrecorder

import android.util.Log
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The §2.3 state machine and §2.4 ring buffer, kept off the JS thread entirely.
 *
 * All mutation happens on a single-threaded executor, so the buffer, the disk,
 * and the reported status can never disagree about what exists.
 */
class SegmentController(
  private val storage: StorageManager,
  private val recorder: SegmentRecorder,
  private val merger: ClipMerger,
  private val listener: Listener,
) {

  interface Listener {
    fun onStateChanged(status: BufferStatus)
    fun onClipFinished(status: BufferStatus)
    fun onSaved(clip: SavedClip)
    fun onError(code: RecorderErrorCode, message: String)
  }

  private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
  private val mergeExecutor = Executors.newSingleThreadExecutor()

  private var config: RecorderConfig = RecorderConfig()
  private var buffer = RingBuffer(config.maxClips)
  private var state: RecorderState = RecorderState.IDLE
  private var sessionId: String? = null
  private var clipIndex = 0
  private var startedAtMs = 0L

  /**
   * Bumped for every clip so a boundary scheduled for a clip that already ended
   * early (an encoder error, say) cannot cut the *next* clip short.
   */
  private var clipGeneration = 0
  private var consecutiveClipFailures = 0

  /** Saves waiting for the clip they cut short to finish finalizing. */
  private val pendingSaves = mutableListOf<PendingSave>()

  private class PendingSave(val trigger: SaveTrigger, val onResult: (Result<SavedClip>) -> Unit)

  fun configure(newConfig: RecorderConfig) = executor.execute {
    config = newConfig
    // Resizing live keeps a mid-drive settings change from restarting capture;
    // shrinking drops the oldest clips immediately (§2.1).
    buffer.resize(newConfig.maxClips).forEach { it.file.delete() }
    emitState()
  }

  fun currentConfig(): RecorderConfig = config

  fun status(): BufferStatus = BufferStatus(
    state = state,
    clipCount = buffer.size,
    maxClips = buffer.capacity,
    bufferedSec = buffer.totalDurationSec,
    bufferedBytes = buffer.totalSizeBytes,
    elapsedSec = if (startedAtMs == 0L) 0.0 else (now() - startedAtMs) / 1000.0,
  )

  /**
   * PLAY — clear leftover temp clips, start the segment loop (§2.3).
   *
   * [onResult] fires on this executor once the outcome is settled, so the
   * caller's promise resolves with the state that actually took effect rather
   * than a snapshot racing the executor.
   */
  fun start(onResult: (Result<BufferStatus>) -> Unit = {}) = executor.execute {
    if (state != RecorderState.IDLE) {
      onResult(Result.success(status()))
      return@execute
    }
    storage.cleanupOrphanedSessions()
    sessionId = UUID.randomUUID().toString().take(8)
    clipIndex = 0
    consecutiveClipFailures = 0
    buffer = RingBuffer(config.maxClips)

    // State only advances once the camera is actually bound — reporting
    // "recording" before prepare() succeeds leaves the UI claiming a live
    // buffer that does not exist.
    runCatching { recorder.prepare(config) }
      .onSuccess {
        startedAtMs = now()
        state = RecorderState.RECORDING
        emitState()
        startNextClip()
        onResult(Result.success(status()))
      }
      .onFailure { error ->
        sessionId?.let(storage::deleteSession)
        sessionId = null
        startedAtMs = 0L
        state = RecorderState.IDLE
        emitState()
        fail(RecorderErrorCode.CAMERA_UNAVAILABLE, error)
        onResult(Result.failure(error))
      }
  }

  /** STOP — cancel the in-flight clip, delete the entire buffer (§2.3). */
  fun stop(onResult: (BufferStatus) -> Unit = {}) = executor.execute {
    if (state == RecorderState.IDLE) {
      onResult(status())
      return@execute
    }
    state = RecorderState.STOPPING
    clipGeneration++
    emitState()

    // STOP discards the in-flight clip, so anything waiting on it never gets
    // its file; resolve those saves now instead of stranding them.
    failPendingSaves("Recording stopped before the clip could be saved")
    recorder.stopClip(discard = true)
    recorder.release()
    buffer.drain().forEach { it.file.delete() }
    sessionId?.let(storage::deleteSession)
    sessionId = null
    startedAtMs = 0L
    state = RecorderState.IDLE
    emitState()
    onResult(status())
  }

  /**
   * SAVE — freeze the window, merge it on the background queue, and start a
   * fresh buffer immediately. Recording never pauses (§2.3).
   *
   * The in-flight clip is *cut* first rather than skipped. Only finished clips
   * may be merged (§10), but simply ignoring the live one means the footage
   * since the last boundary is lost — and for the first `clipDurationSec` of a
   * session, or in any window straight after a Save, that is everything there
   * is. Pressing Save one second in has to keep that second, so the clip is
   * closed properly and the merge waits for it.
   */
  fun save(trigger: SaveTrigger, onResult: (Result<SavedClip>) -> Unit) = executor.execute {
    if (state != RecorderState.RECORDING) {
      onResult(Result.failure(IllegalStateException("Not recording")))
      return@execute
    }
    pendingSaves += PendingSave(trigger, onResult)
    // Retires the boundary already scheduled for this clip, so that it cannot
    // fire later and cut the *next* clip short.
    clipGeneration++
    recorder.stopClip(discard = false)
  }

  /**
   * Resolve every Save waiting on the clip that was just cut.
   *
   * Runs on the executor after that clip has landed in the buffer, so the
   * window this snapshots is exactly the footage the user asked for. Several
   * waiting saves share one merge: merging twice would hand the same files to
   * two jobs that each delete them when done.
   */
  private fun flushPendingSaves() {
    if (pendingSaves.isEmpty()) return
    val waiting = pendingSaves.toList()
    pendingSaves.clear()

    val snapshot = buffer.snapshot()
    if (snapshot.isEmpty()) {
      // Only reachable when the cut clip yielded no usable file at all — a tap
      // landing within a few frames of Play, or an encoder that dropped the
      // segment outright.
      val error = IllegalStateException("Nothing recorded yet — try again in a moment")
      waiting.forEach { it.onResult(Result.failure(error)) }
      return
    }
    // The new window starts here; ownership of the snapshot's files passes to
    // the merge, which deletes them once the merged file is on disk.
    buffer = RingBuffer(config.maxClips)
    emitState()

    val destination = storage.savedFile()
    val trigger = waiting.first().trigger
    mergeExecutor.execute {
      val result = runCatching { merger.merge(snapshot, destination, config, trigger) }
      result
        .onSuccess { clip ->
          snapshot.forEach { it.file.delete() }
          listener.onSaved(clip)
        }
        .onFailure { fail(RecorderErrorCode.MERGE_FAILED, it) }
      waiting.forEach { it.onResult(result) }
    }
  }

  /** Never leave a Save promise unresolved — JS would hang on it forever. */
  private fun failPendingSaves(reason: String) {
    if (pendingSaves.isEmpty()) return
    val waiting = pendingSaves.toList()
    pendingSaves.clear()
    waiting.forEach { it.onResult(Result.failure(IllegalStateException(reason))) }
  }

  fun release() {
    executor.execute { recorder.release() }
    executor.shutdown()
    mergeExecutor.shutdown()
  }

  // --- segment loop -------------------------------------------------------

  private fun startNextClip() {
    val session = sessionId ?: return
    val output = storage.clipFile(session, ++clipIndex)
    val generation = ++clipGeneration
    recorder.startClip(
      output = output,
      onFinished = { clip -> executor.execute { onClipFinished(clip) } },
      onError = { error -> executor.execute { onClipFailed(error) } },
    )
    // The clip boundary is scheduled, not polled — nothing wakes the CPU every
    // second just to check the buffer (§6).
    executor.schedule(
      {
        if (state == RecorderState.RECORDING && clipGeneration == generation) {
          recorder.stopClip(discard = false)
        }
      },
      (config.clipDurationSec * 1000).toLong(),
      TimeUnit.MILLISECONDS,
    )
  }

  /**
   * §2.3 / §2.4 — append, evict the oldest if we were already full, delete the
   * evicted file, and immediately roll into the next clip.
   */
  private fun onClipFinished(clip: Clip) {
    if (state != RecorderState.RECORDING) {
      clip.file.delete()
      return
    }
    consecutiveClipFailures = 0
    buffer.push(clip)?.let { evicted ->
      if (!evicted.file.delete()) {
        Log.w(TAG, "Failed to delete evicted clip ${evicted.file.name}")
      }
    }
    listener.onClipFinished(status())
    startNextClip()
    // After the new clip is rolling, so a Save never costs the buffer a frame.
    flushPendingSaves()
  }

  /**
   * A clip that fails to finalize costs one clip, not the drive: the loop rolls
   * into the next segment. Only a run of failures — a camera that is genuinely
   * gone — stops the session, so the buffer never sits silently dead while the
   * UI still shows "recording".
   */
  private fun onClipFailed(error: Throwable) {
    if (state != RecorderState.RECORDING) return
    consecutiveClipFailures++
    fail(RecorderErrorCode.UNKNOWN, error)
    // A Save that cut this clip is waiting on a file that will never arrive.
    // Merge whatever complete clips the window still holds rather than hang.
    flushPendingSaves()

    if (consecutiveClipFailures >= MAX_CONSECUTIVE_CLIP_FAILURES) {
      fail(
        RecorderErrorCode.CAMERA_UNAVAILABLE,
        IllegalStateException("Recording stopped after $consecutiveClipFailures failed clips"),
      )
      stop()
      return
    }

    executor.schedule(
      { if (state == RecorderState.RECORDING) startNextClip() },
      CLIP_RETRY_DELAY_MS,
      TimeUnit.MILLISECONDS,
    )
  }

  private fun emitState() = listener.onStateChanged(status())

  private fun fail(code: RecorderErrorCode, error: Throwable) {
    Log.e(TAG, "Recorder error: $code", error)
    listener.onError(code, error.message ?: code.jsValue)
  }

  private fun now() = System.currentTimeMillis()

  private companion object {
    const val TAG = "LoopCam/Segment"

    /** A run this long means the camera is gone, not glitching. */
    const val MAX_CONSECUTIVE_CLIP_FAILURES = 3
    const val CLIP_RETRY_DELAY_MS = 500L
  }
}

/** Convenience for tests and the merge queue. */
internal fun File.sizeOrZero(): Long = if (exists()) length() else 0L
