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

  /**
   * The timestamp only resolves to the second, so two saves inside the same
   * second would otherwise overwrite each other — and the filename is the
   * clip's identity (§7.1), so a collision loses an incident outright.
   */
  fun savedFile(timestamp: Date = Date()): File {
    val stamp = synchronized(TIMESTAMP_FORMAT) { TIMESTAMP_FORMAT.format(timestamp) }
    var candidate = File(savedRoot, "Incident_$stamp.mp4")
    var suffix = 2
    while (candidate.exists()) {
      candidate = File(savedRoot, "Incident_${stamp}_$suffix.mp4")
      suffix++
    }
    return candidate
  }

  fun metadataFileFor(video: File): File =
    File(video.parentFile, video.nameWithoutExtension + ".json")

  /**
   * §7.2 — protection is a marker file rather than app state: the saved
   * directory is the index, and a flag that lives only in memory silently
   * un-protects every clip on process death, which is exactly when the budget
   * sweep is most likely to run.
   */
  fun protectionMarkerFor(video: File): File =
    File(video.parentFile, video.nameWithoutExtension + ".protected")

  fun isProtected(video: File): Boolean = protectionMarkerFor(video).exists()

  fun setProtected(video: File, isProtected: Boolean) {
    val marker = protectionMarkerFor(video)
    if (isProtected) {
      if (!marker.exists()) marker.createNewFile()
    } else {
      marker.delete()
    }
  }

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

  /**
   * §7.2 — the budget sweep. Oldest-first among unprotected clips until both
   * the byte budget and the count limit are met. Returns the ids removed, so
   * the caller can tell JS which rows just vanished from under it.
   *
   * Protected clips are counted against the budget but never deleted: a user
   * who locks 5 GB of footage has told us to stop reclaiming space, and quietly
   * overriding that would be worse than running out.
   */
  fun enforceBudget(): List<String> {
    val files = savedRoot.listFiles { f: File -> f.extension == "mp4" } ?: return emptyList()
    var totalBytes = files.sumOf { it.length() }
    var count = files.size
    val removed = mutableListOf<String>()

    val candidates = files.filterNot { isProtected(it) }.sortedBy { it.lastModified() }
    for (file in candidates) {
      if (totalBytes <= SAVED_STORAGE_BUDGET_BYTES && count <= SAVED_CLIP_COUNT_LIMIT) break
      val size = file.length()
      val id = file.nameWithoutExtension
      if (!file.delete()) continue
      metadataFileFor(file).delete()
      protectionMarkerFor(file).delete()
      totalBytes -= size
      count -= 1
      removed += id
    }
    return removed
  }

  /**
   * Count and bytes of the saved directory, without opening a single file.
   *
   * The gallery's clip list needs a duration per clip, which costs a
   * MediaMetadataRetriever each; the storage figures do not. Reusing the clip
   * list here would put ~50 container reads on the save path, every save,
   * while the buffer is still recording.
   */
  fun savedFootprint(): Pair<Int, Long> {
    val files = savedRoot.listFiles { f: File -> f.extension == "mp4" } ?: return 0 to 0L
    return files.size to files.sumOf { it.length() }
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
