package expo.modules.loopcamrecorder

import android.content.Context
import android.os.StatFs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * §7.1 file naming & layout.
 *
 *   <app-private>/LoopCam/tmp/session_<sessionId>/clip_0001.mp4   (rolling buffer)
 *   <app-private>/LoopCam/saved/Incident_<yyyy-MM-dd_HH-mm-ss>.mp4
 *   <app-private>/LoopCam/saved/Incident_<yyyy-MM-dd_HH-mm-ss>.json
 *
 * Everything lives in app-private external storage, so no MediaStore or
 * WRITE_EXTERNAL_STORAGE dance under scoped storage. Exporting a saved clip to
 * the user's gallery is a deliberate, separate action.
 */
class StorageManager(private val context: Context) {

  private val root: File
    get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "LoopCam")

  val tmpRoot: File get() = File(root, "tmp").apply { mkdirs() }
  val savedRoot: File get() = File(root, "saved").apply { mkdirs() }

  fun sessionDir(sessionId: String): File =
    File(tmpRoot, "session_$sessionId").apply { mkdirs() }

  fun clipFile(sessionId: String, index: Int): File =
    File(sessionDir(sessionId), "clip_%04d.mp4".format(index))

  fun savedFile(timestamp: Date = Date()): File =
    File(savedRoot, "Incident_${TIMESTAMP_FORMAT.format(timestamp)}.mp4")

  fun metadataFileFor(video: File): File =
    File(video.parentFile, video.nameWithoutExtension + ".json")

  /** STOP wipes the whole session directory (§7.1). */
  fun deleteSession(sessionId: String) {
    sessionDir(sessionId).deleteRecursively()
  }

  /**
   * §7.2 — the temp buffer must never survive a crash. Any session directory
   * left behind is orphaned by definition, since only one session is live at a
   * time; sweep them on launch. Returns how many were removed.
   */
  fun cleanupOrphanedSessions(activeSessionId: String? = null): Int {
    val sessions = tmpRoot.listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
      ?: return 0
    var removed = 0
    for (dir in sessions) {
      if (activeSessionId != null && dir.name == "session_$activeSessionId") continue
      if (dir.deleteRecursively()) removed++
    }
    return removed
  }

  fun storageStatus(savedClipCount: Int, savedBytes: Long): StorageStatus {
    val stat = StatFs(root.absolutePath)
    val free = stat.availableBlocksLong * stat.blockSizeLong
    return StorageStatus(
      freeBytes = free,
      savedBytes = savedBytes,
      savedClipCount = savedClipCount,
      lowSpaceWarning = free < LOW_SPACE_WARNING_BYTES,
    )
  }

  companion object {
    /** Warn the user below ~1 GB free (§7.2). */
    const val LOW_SPACE_WARNING_BYTES = 1_000_000_000L

    /** Auto-delete the oldest unprotected saved clip past this budget (§7.2). */
    const val SAVED_STORAGE_BUDGET_BYTES = 5_000_000_000L
    const val SAVED_CLIP_COUNT_LIMIT = 50

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
  }
}
