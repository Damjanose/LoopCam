package expo.modules.loopcamrecorder

import androidx.annotation.MainThread
import androidx.camera.core.Preview

/**
 * The one seam between the mounted preview view and the live capture session.
 *
 * The two have independent lifetimes on purpose (§3.1): the buffer is owned by
 * the foreground service and the view comes and goes with the React tree, so
 * neither can hold a direct reference to the other. Whichever arrives second
 * finds the other already published here, which is why a preview mounted after
 * Play still lights up — and why unmounting it drops the surface without
 * touching the recording.
 *
 * Main thread only: CameraX requires `setSurfaceProvider` there, and confining
 * the whole object to one thread is cheaper than a lock.
 */
object CameraPreviewBus {

  private var provider: Preview.SurfaceProvider? = null
  private var sink: ((Preview.SurfaceProvider?) -> Unit)? = null

  /** Called by the view as it attaches (non-null) and detaches (null). */
  @MainThread
  fun publish(newProvider: Preview.SurfaceProvider?) {
    provider = newProvider
    sink?.invoke(newProvider)
  }

  /** Called by the recorder once its `Preview` use case is bound. */
  @MainThread
  fun subscribe(newSink: ((Preview.SurfaceProvider?) -> Unit)?) {
    sink = newSink
    newSink?.invoke(provider)
  }
}
