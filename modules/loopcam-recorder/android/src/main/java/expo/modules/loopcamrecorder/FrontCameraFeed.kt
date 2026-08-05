package expo.modules.loopcamrecorder

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicReference

/**
 * The front camera, reduced to "the latest small upright bitmap".
 *
 * In `both` mode the front camera is not recorded in its own right — it exists
 * only to be drawn into the corner of the back camera's frame. So it is bound
 * as a bare [ImageAnalysis] at a deliberately small resolution rather than as a
 * second preview or a second recording: the inset occupies under a tenth of the
 * frame, and decoding the front camera at capture resolution to draw it a third
 * of an inch wide is battery spent on pixels nobody will ever see (§6).
 *
 * Two threads meet here — CameraX calls [analyze] on the analyzer's executor,
 * [WatermarkOverlay] reads on its own draw thread — and they are decoupled by a
 * single [AtomicReference]. There is no lock, and deliberately no recycling:
 * see [publish].
 */
internal class FrontCameraFeed : ImageAnalysis.Analyzer {

  private class Timed(val bitmap: Bitmap, val atMs: Long)

  private val latest = AtomicReference<Timed?>(null)

  /** Wall clock of the last publish, so [analyze] can drop frames it does not need. */
  private var lastPublishedMs = 0L

  /**
   * The frame to draw, or null if the front camera has gone quiet.
   *
   * Staleness is checked *here*, at draw time, rather than when publishing: a
   * camera that stops delivering never calls back to retract anything, and a
   * frozen inset burned into footage that is timestamped as live is the one
   * failure mode that would make a saved clip actively misleading.
   */
  fun current(nowMs: Long): Bitmap? {
    val frame = latest.get() ?: return null
    return if (nowMs - frame.atMs > WatermarkStyle.Pip.MAX_FRAME_AGE_MS) null else frame.bitmap
  }

  override fun analyze(image: ImageProxy) {
    // Try/finally around the whole body: an ImageProxy that is not closed stalls
    // the analyzer permanently once the queue fills, which would freeze the
    // inset rather than merely skipping a frame.
    try {
      val nowMs = System.currentTimeMillis()
      if (nowMs - lastPublishedMs < MIN_PUBLISH_INTERVAL_MS) return
      lastPublishedMs = nowMs
      publish(upright(image), nowMs)
    } catch (_: Throwable) {
      // A frame that will not convert is not a reason to stop recording the
      // road; the previous one stands until it ages out.
    } finally {
      image.close()
    }
  }

  /**
   * Hands the new frame over and drops the old one on the floor.
   *
   * The displaced bitmap is *not* recycled. The draw thread may be part-way
   * through compositing it at this exact moment, and there is no handshake
   * between the two — recycling here would be a use-after-free that surfaces as
   * a crash mid-drive. GC collects it a moment later, which at 15 fps of a
   * ~640x360 bitmap is a cost worth paying for a pipeline that cannot fault.
   */
  private fun publish(bitmap: Bitmap, atMs: Long) {
    latest.set(Timed(bitmap, atMs))
  }

  /**
   * The sensor's buffer, rotated the way it will be displayed.
   *
   * Done once per published frame rather than per drawn frame: the overlay
   * draws every frame the back camera produces and would otherwise repeat this
   * rotation two to four times for the same picture.
   *
   * Not mirrored. A mirrored selfie is the phone-camera convention, but this
   * footage is evidence — text inside the cabin has to read the right way round.
   */
  private fun upright(image: ImageProxy): Bitmap {
    val source = image.toBitmap()
    val rotation = image.imageInfo.rotationDegrees
    if (rotation == 0) return source
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
  }

  fun clear() {
    latest.set(null)
    lastPublishedMs = 0L
  }

  private companion object {
    /**
     * ~15 fps. The back camera runs at 30; publishing at its rate would double
     * the conversion cost for an inset whose staleness is not observable at a
     * third of an inch wide.
     */
    const val MIN_PUBLISH_INTERVAL_MS = 66L
  }
}
