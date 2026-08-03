package expo.modules.loopcamrecorder

import java.io.File

/**
 * A finished, fully flushed segment sitting in the temp buffer.
 *
 * Only closed clips ever enter the buffer — the in-progress recording stays out
 * of it, which is what keeps a Save from merging a half-written file (§10).
 */
data class Clip(
  val file: File,
  val durationSec: Double,
  val sizeBytes: Long,
  val startedAtMs: Long,
)

/**
 * §2.4 — the buffer is a fixed-capacity ring, not a list we re-scan. Pushing
 * into a full ring evicts the oldest clip, so delete-oldest falls out of the
 * data structure instead of manual cleanup that can drift out of sync with disk.
 *
 * Not thread-safe by itself; [SegmentController] owns it from a single executor.
 */
class RingBuffer(capacity: Int) {
  var capacity: Int = capacity
    private set

  private val clips = ArrayDeque<Clip>()

  val size: Int get() = clips.size
  val isFull: Boolean get() = clips.size >= capacity
  val totalDurationSec: Double get() = clips.sumOf { it.durationSec }
  val totalSizeBytes: Long get() = clips.sumOf { it.sizeBytes }

  /** Append a clip, returning the evicted one (which the caller must delete). */
  fun push(clip: Clip): Clip? {
    val evicted = if (isFull) clips.removeFirstOrNull() else null
    clips.addLast(clip)
    return evicted
  }

  /**
   * Freeze the current window for a Save. The buffer keeps ownership of nothing
   * afterwards: the merge reads the snapshot while a brand-new window fills.
   */
  fun snapshot(): List<Clip> = clips.toList()

  fun drain(): List<Clip> {
    val all = clips.toList()
    clips.clear()
    return all
  }

  /**
   * Re-capacity live, e.g. when the user changes buffer length mid-drive.
   * Shrinking evicts the oldest clips immediately.
   */
  fun resize(newCapacity: Int): List<Clip> {
    capacity = newCapacity.coerceAtLeast(1)
    val evicted = mutableListOf<Clip>()
    while (clips.size > capacity) {
      clips.removeFirstOrNull()?.let(evicted::add)
    }
    return evicted
  }
}
