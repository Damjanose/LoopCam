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
   * fresh buffer immediately. Recording never pauses (§2.3); the in-progress
   * clip is deliberately excluded from the snapshot so a merge can never read a
   * half-written file (§10), and it simply becomes the first clip of the new
   * window.
   */
  fun save(trigger: SaveTrigger, onResult: (Result<SavedClip>) -> Unit) = executor.execute {
    if (state != RecorderState.RECORDING) {
      onResult(Result.failure(IllegalStateException("Not recording")))
      return@execute
    }
    val snapshot = buffer.snapshot()
    if (snapshot.isEmpty()) {
      onResult(Result.failure(IllegalStateException("Buffer is empty")))
      return@execute
    }
    // The new window starts here; ownership of the snapshot's files passes to
    // the merge, which deletes them once the merged file is on disk.
    buffer = RingBuffer(config.maxClips)
    emitState()

    val destination = storage.savedFile()
    mergeExecutor.execute {
      val result = runCatching { merger.merge(snapshot, destination, config, trigger) }
      result
        .onSuccess { clip ->
          snapshot.forEach { it.file.delete() }
          listener.onSaved(clip)
        }
        .onFailure { fail(RecorderErrorCode.MERGE_FAILED, it) }
      onResult(result)
    }
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
