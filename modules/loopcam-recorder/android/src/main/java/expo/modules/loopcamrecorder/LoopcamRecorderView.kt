package expo.modules.loopcamrecorder

import android.content.Context
import androidx.camera.view.PreviewView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

/**
 * Live preview surface. It is a *window onto* the capture session, never the
 * owner of it — mounting or unmounting this view must not start or stop the
 * rolling buffer, which is what lets the UI navigate away while recording
 * continues.
 */
class LoopcamRecorderView(context: Context, appContext: AppContext) :
  ExpoView(context, appContext) {

  private val previewView = PreviewView(context).also { addView(it) }

  init {
    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
  }

  fun setLens(lens: String) {
    // TODO(phase-1): switch CameraSelector on the live session; front-facing is
    // only meaningful once multi-camera (§8, v2) lands.
  }

  fun setResizeMode(mode: String) {
    previewView.scaleType = when (mode) {
      "contain" -> PreviewView.ScaleType.FIT_CENTER
      else -> PreviewView.ScaleType.FILL_CENTER
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // TODO(phase-1): hand previewView.surfaceProvider to CameraXSegmentRecorder's
    // Preview use case so the same session feeds preview and buffer.
  }
}
