package expo.modules.loopcamrecorder

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The burned-in wall clock, and — in `both` mode — the front camera's inset
 * picture beside it.
 *
 * Both live in the same effect because they are the same job: everything drawn
 * onto the frame between the sensor and the encoder, in displayed coordinates,
 * on one canvas pass.
 *
 * Drawn at *capture*, not at merge. Save is a stream-copy concat, and drawing
 * text at merge time would mean decoding, compositing and re-encoding the whole
 * window on every Save — seconds to minutes of CPU and the loss of the single
 * biggest battery lever in §6. One GPU composite per frame is the cheaper end of
 * that trade by a wide margin.
 *
 * [OverlayEffect] can target the preview and the video stream at once, so the
 * viewfinder shows the very thing the file will contain instead of an
 * approximation of it.
 *
 * Everything here runs on [thread] — CameraX calls the draw listener there and
 * nowhere else, which is why the caches below need no synchronisation.
 */
internal class WatermarkOverlay(
  targets: Int,
  /** The front camera in `both` mode; null in the single-camera modes. */
  private val pip: FrontCameraFeed? = null,
) : AutoCloseable {

  private val thread = HandlerThread("loopcam-watermark").apply { start() }

  /**
   * A queue depth of 0 keeps the effect from buffering frames: the timestamp is
   * only honest if the frame it is drawn on is the frame it is measuring.
   */
  val effect: OverlayEffect = OverlayEffect(targets, 0, Handler(thread.looper)) { /* drop */ }

  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = WatermarkStyle.TEXT_COLOR
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
  }
  private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = WatermarkStyle.PLATE_COLOR
  }
  private val formatter = SimpleDateFormat(WatermarkStyle.PATTERN, Locale.US)

  /** FILTER_BITMAP: the inset is a downscale, and a nearest-neighbour one crawls. */
  private val pipPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val pipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = WatermarkStyle.Pip.BORDER_COLOR
  }
  /** Reused rather than allocated per frame — this runs 30 times a second. */
  private val pipClip = Path()

  /**
   * Frame timestamps are on a monotonic clock; the watermark is a wall clock.
   * The offset between them is sampled once, so the label advances with the
   * footage instead of stepping whenever the system clock is nudged.
   */
  private val uptimeToWallMs = System.currentTimeMillis() - SystemClock.uptimeMillis()

  /** The text is redrawn per frame but only re-*formatted* when the second turns. */
  private var cachedSecond = Long.MIN_VALUE
  private var cachedText = ""

  init {
    effect.setOnDrawListener { frame ->
      draw(frame)
      // False would drop the frame from the stream entirely.
      true
    }
  }

  private fun draw(frame: Frame) {
    val canvas = frame.overlayCanvas
    // The overlay surface is reused between frames, so last frame's text is
    // still on it.
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    val crop = frame.cropRect
    if (crop.isEmpty) return
    val rotation = ((frame.rotationDegrees % 360) + 360) % 360
    val sideways = rotation == 90 || rotation == 270
    val displayWidth = (if (sideways) crop.height() else crop.width()).toFloat()
    val displayHeight = (if (sideways) crop.width() else crop.height()).toFloat()

    canvas.save()
    // Draw in *displayed* coordinates. The buffer is landscape and the
    // container tells players to rotate it; text laid out in buffer space would
    // come out on its side in every player, which is the failure this mapping
    // exists to prevent.
    applyDisplayTransform(canvas, crop, rotation)
    // Inset first, clock second. They do not overlap by construction — opposite
    // corners — but fixing the order means a later change to either one's
    // geometry cannot end up burying the timestamp.
    drawPip(canvas, displayWidth, displayHeight)
    drawStamp(canvas, frame.timestampNanos, displayWidth, displayHeight)
    canvas.restore()
  }

  /**
   * Maps the displayed frame's coordinate space onto the buffer.
   *
   * Display is the crop region rotated clockwise by [rotation]; this is that
   * rotation inverted, so `(0,0)` is the top-left of the picture as a player
   * will show it and `(w,h)` its bottom-right.
   */
  private fun applyDisplayTransform(canvas: Canvas, crop: Rect, rotation: Int) {
    canvas.translate(crop.left.toFloat(), crop.top.toFloat())
    val w = crop.width().toFloat()
    val h = crop.height().toFloat()
    when (rotation) {
      90 -> {
        canvas.translate(0f, h)
        canvas.rotate(-90f)
      }
      180 -> {
        canvas.translate(w, h)
        canvas.rotate(180f)
      }
      270 -> {
        canvas.translate(w, 0f)
        canvas.rotate(90f)
      }
    }
  }

  /**
   * The front camera in the top-right corner, centre-cropped to fill.
   *
   * Scaled with a source rect rather than by pre-scaling the bitmap: the
   * analyzer already delivers something close to this size, and letting the
   * canvas do the last bit of scaling keeps the whole thing one draw call.
   *
   * A null frame — no `both` mode, or a front camera that has gone quiet for
   * longer than the staleness window — simply draws nothing. The corner going
   * empty reads as "the front camera stopped"; a frozen picture would not.
   */
  private fun drawPip(canvas: Canvas, width: Float, height: Float) {
    val bitmap = pip?.current(System.currentTimeMillis()) ?: return
    if (bitmap.width <= 0 || bitmap.height <= 0) return

    val pipWidth = width * WatermarkStyle.Pip.WIDTH_FRACTION
    val pipHeight = pipWidth / WatermarkStyle.Pip.ASPECT
    val inset = minOf(width, height) * WatermarkStyle.INSET_FRACTION
    val right = width - inset
    val left = right - pipWidth
    val top = inset
    val bottom = top + pipHeight
    val dst = RectF(left, top, right, bottom)

    // Centre-crop: the widest (or tallest) centred slice of the source that
    // matches the destination's aspect. Fitting instead would put black bars
    // inside an already-small inset, which reads as a dead feed.
    val sourceAspect = bitmap.width.toFloat() / bitmap.height
    val targetAspect = pipWidth / pipHeight
    val src = if (sourceAspect > targetAspect) {
      val cropWidth = bitmap.height * targetAspect
      val x = ((bitmap.width - cropWidth) / 2f).toInt()
      Rect(x, 0, x + cropWidth.toInt(), bitmap.height)
    } else {
      val cropHeight = bitmap.width / targetAspect
      val y = ((bitmap.height - cropHeight) / 2f).toInt()
      Rect(0, y, bitmap.width, y + cropHeight.toInt())
    }

    val corner = pipHeight * WatermarkStyle.Pip.CORNER_FRACTION
    pipClip.reset()
    pipClip.addRoundRect(dst, corner, corner, Path.Direction.CW)

    canvas.save()
    canvas.clipPath(pipClip)
    canvas.drawBitmap(bitmap, src, dst, pipPaint)
    canvas.restore()

    pipBorderPaint.strokeWidth = minOf(width, height) * WatermarkStyle.Pip.BORDER_WIDTH_FRACTION
    canvas.drawRoundRect(dst, corner, corner, pipBorderPaint)
  }

  private fun drawStamp(canvas: Canvas, frameTimestampNanos: Long, width: Float, height: Float) {
    val text = textFor(frameTimestampNanos)

    val textSize = height * WatermarkStyle.TEXT_HEIGHT_FRACTION
    textPaint.textSize = textSize
    val metrics = textPaint.fontMetrics
    val padX = textSize * WatermarkStyle.PAD_X_FRACTION
    val padY = textSize * WatermarkStyle.PAD_Y_FRACTION
    val plateWidth = textPaint.measureText(text) + padX * 2
    val plateHeight = (metrics.descent - metrics.ascent) + padY * 2

    val inset = minOf(width, height) * WatermarkStyle.INSET_FRACTION
    val right = width - inset
    val bottom = height - inset
    val left = right - plateWidth
    val top = bottom - plateHeight
    val corner = plateHeight * WatermarkStyle.CORNER_FRACTION

    canvas.drawRoundRect(left, top, right, bottom, corner, corner, platePaint)
    canvas.drawText(text, left + padX, top + padY - metrics.ascent, textPaint)
  }

  /**
   * The frame's own clock, not the wall clock read at draw time: the two differ
   * by however long the pipeline took, and labelling a frame with the moment it
   * reached the compositor rather than the moment it was captured is exactly the
   * error a dashcam timestamp must not make.
   *
   * A frame whose timestamp turns out not to be on the uptime clock — some
   * devices report the boot-time domain instead — would land days away from now.
   * Rather than burn a wrong date into the footage, that falls back to the
   * current time, which is off by milliseconds at worst.
   */
  private fun textFor(frameTimestampNanos: Long): String {
    val fromFrame = frameTimestampNanos / 1_000_000L + uptimeToWallMs
    val now = System.currentTimeMillis()
    val wallMs = if (kotlin.math.abs(now - fromFrame) > CLOCK_SANITY_MS) now else fromFrame

    val second = wallMs / 1000L
    if (second != cachedSecond) {
      cachedSecond = second
      cachedText = formatter.format(Date(wallMs))
    }
    return cachedText
  }

  override fun close() {
    effect.close()
    thread.quitSafely()
  }

  companion object {
    /** Preview and file get the same treatment — the viewfinder is WYSIWYG. */
    const val SESSION_TARGETS = CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE

    /** Standby has no video stream to target. */
    const val PREVIEW_TARGETS = CameraEffect.PREVIEW

    /** How far a frame's clock may sit from now before it is not believed. */
    private const val CLOCK_SANITY_MS = 5_000L
  }
}
