package expo.modules.loopcamrecorder

import android.content.Context
import android.view.ViewGroup
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

/**
 * Live preview surface. It is a *window onto* the capture session, never the
 * owner of it — mounting or unmounting this view must not start or stop the
 * rolling buffer, which is what lets the UI navigate away while recording
 * continues.
 *
 * All it does is publish its surface to [CameraPreviewBus]; whether anything is
 * recording is none of its business.
 */
class LoopcamRecorderView(context: Context, appContext: AppContext) :
  ExpoView(context, appContext) {

  private val previewView = PreviewView(context).also {
    it.layoutParams = ViewGroup.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.MATCH_PARENT,
    )
    addView(it)
  }

  /** Cached: CameraX wants the identical provider instance across attaches. */
  private val surfaceProvider: Preview.SurfaceProvider get() = previewView.surfaceProvider

  init {
    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
    // COMPATIBLE (TextureView) rather than PERFORMANCE: the preview sits under a
    // React-managed overlay, and a SurfaceView would punch through it.
    previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
  }

  fun setResizeMode(mode: String) {
    previewView.scaleType = when (mode) {
      "contain" -> PreviewView.ScaleType.FIT_CENTER
      else -> PreviewView.ScaleType.FILL_CENTER
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    CameraPreviewBus.publish(surfaceProvider)
  }

  override fun onDetachedFromWindow() {
    // Dropping the surface stops the picture, not the recording — the
    // VideoCapture use case stays bound to the service.
    CameraPreviewBus.publish(null)
    super.onDetachedFromWindow()
  }

  /** Re-run the Android layout pass when the PreviewView asks for one. */
  override val shouldUseAndroidLayout = true

  override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
    // React Native lays out this view but never measures the children we add
    // natively, so without measuring here the PreviewView stays 0x0 and the
    // preview is invisible even once the camera is bound.
    val width = r - l
    val height = b - t
    previewView.measure(
      MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
      MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
    )
    previewView.layout(0, 0, width, height)
  }
}
