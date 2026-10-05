package echo.music.iad1tya.wear.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.OfflineKeys
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A song stored on the watch. */
@Immutable
data class OfflineSong(
  val id: String,
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val mimeType: String,
  val audioPath: String,
  val artworkPath: String?,
  val sizeBytes: Long,
)

sealed interface SyncStatus {
  data object Idle : SyncStatus

  /** Request sent, waiting for the phone's manifest. */
  data object Requesting : SyncStatus

  data class Syncing(val done: Int, val total: Int) : SyncStatus

  data class Finished(val added: Int) : SyncStatus

  data object Failed : SyncStatus
}

/**
 * The watch's own music library: songs copied from the phone, kept in app-private storage so they
 * play with no phone around.
 *
 * Layout under `filesDir/offline`: `<id>.audio`, `<id>.jpg` and an `index.json` holding the order
 * the phone asked for plus each song's metadata. The phone is the source of truth: every manifest
 * replaces the set, so songs the phone no longer offers are deleted here.
 */
class OfflineLibrary private constructor(context: Context) {
  private val appContext = context.applicationContext
  private val dir = File(appContext.filesDir, "offline").apply { mkdirs() }
  private val indexFile = File(dir, INDEX_FILE)
  private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()

  private var order: List<String> = emptyList()
  private val byId = LinkedHashMap<String, OfflineSong>()

  private val _songs = MutableStateFlow<List<OfflineSong>>(emptyList())
  val songs: StateFlow<List<OfflineSong>> = _songs.asStateFlow()

  private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
  val status: StateFlow<SyncStatus> = _status.asStateFlow()

  private val _limit = MutableStateFlow(prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT))
  val limit: StateFlow<Int> = _limit.asStateFlow()

  private var expectedTotal = 0
  private var received = 0

  init {
    load()
  }

  fun setLimit(value: Int) {
    val v = value.coerceIn(WearProtocol.OFFLINE_MIN_LIMIT, WearProtocol.OFFLINE_MAX_LIMIT)
    prefs.edit().putInt(KEY_LIMIT, v).apply()
    _limit.value = v
  }

  val totalSizeBytes: Long
    get() = synchronized(lock) { byId.values.sumOf { it.sizeBytes } }

  fun haveIds(): List<String> = synchronized(lock) { byId.keys.toList() }

  // region sync lifecycle

  /** Marks that a request went out; fails by itself if the phone never answers. */
  fun onRequested() {
    _status.value = SyncStatus.Requesting
    scope.launch {
      delay(RESPONSE_TIMEOUT_MS)
      if (_status.value == SyncStatus.Requesting) _status.value = SyncStatus.Failed
    }
  }

  /** The phone's answer: keep exactly [ids] (in that order) and expect [missing] to be sent. */
  fun applyManifest(ids: List<String>, missing: List<String>) {
    synchronized(lock) {
      order = ids.filter { isValidId(it) }
      val keep = order.toSet()
      val removed = byId.keys.filter { it !in keep }
      removed.forEach { removeFiles(it) }
      byId.keys.removeAll(removed.toSet())
      expectedTotal = missing.size
      received = 0
      publish()
      persist()
    }
    _status.value =
      if (missing.isEmpty()) SyncStatus.Finished(added = 0) else SyncStatus.Syncing(0, missing.size)
  }

  /** The phone has finished (or given up). */
  fun onDone() {
    synchronized(lock) { cleanOrphans() }
    val current = _status.value
    _status.value =
      when {
        current is SyncStatus.Syncing && received >= expectedTotal -> SyncStatus.Finished(received)
        current is SyncStatus.Syncing -> SyncStatus.Failed
        current is SyncStatus.Requesting -> SyncStatus.Failed
        else -> current
      }
  }

  /**
   * Reads one song frame ([WearProtocol.OfflineFrame]) from [input] and stores it.
   *
   * @return whether the song was stored
   */
  fun receiveSong(input: InputStream): Boolean {
    val data = DataInputStream(input.buffered())
    val headerLength = data.readInt()
    if (headerLength !in 1..WearProtocol.OfflineFrame.MAX_HEADER_BYTES) return false
    val header = JSONObject(String(ByteArray(headerLength).also { data.readFully(it) }, Charsets.UTF_8))

    val id = header.getString(OfflineKeys.ID)
    val audioSize = header.getLong(OfflineKeys.AUDIO_SIZE)
    val artworkSize = header.optInt(OfflineKeys.ARTWORK_SIZE, 0)
    if (!isValidId(id) || audioSize <= 0) return false
    if (dir.usableSpace < audioSize + artworkSize + MIN_FREE_BYTES) {
      Log.w(TAG, "Not enough space for $id, skipping")
      return false
    }

    val audioTmp = File(dir, "$id.audio.tmp")
    val artworkTmp = File(dir, "$id.jpg.tmp")
    try {
      if (artworkSize > 0) {
        artworkTmp.outputStream().use { copyExactly(data, it, artworkSize.toLong()) }
      }
      audioTmp.outputStream().use { copyExactly(data, it, audioSize) }
    } catch (e: IOException) {
      audioTmp.delete()
      artworkTmp.delete()
      throw e
    }

    val audioFile = File(dir, "$id.audio")
    val artworkFile = File(dir, "$id.jpg")
    audioTmp.renameTo(audioFile)
    val hasArtwork = artworkSize > 0 && artworkTmp.renameTo(artworkFile)

    val song =
      OfflineSong(
        id = id,
        title = header.optString(OfflineKeys.TITLE),
        artist = header.optString(OfflineKeys.ARTIST),
        album = header.optString(OfflineKeys.ALBUM),
        durationMs = header.optLong(OfflineKeys.DURATION_MS, 0L),
        mimeType = header.optString(OfflineKeys.MIME_TYPE),
        audioPath = audioFile.absolutePath,
        artworkPath = if (hasArtwork) artworkFile.absolutePath else null,
        sizeBytes = audioSize + if (hasArtwork) artworkSize else 0,
      )
    synchronized(lock) {
      byId[id] = song
      received++
      publish()
      persist()
    }
    _status.value = SyncStatus.Syncing(received, expectedTotal)
    return true
  }

  // endregion

  // region storage

  private fun publish() {
    val ordered = order.mapNotNull { byId[it] }
    // Songs not (yet) in the ordering, e.g. after an interrupted sync, go last instead of vanishing.
    val rest = byId.values.filter { it.id !in order }
    _songs.value = ordered + rest
  }

  private fun persist() {
    val json =
      JSONObject()
        .put("order", JSONArray(order))
        .put(
          "songs",
          JSONArray(
            byId.values.map {
              JSONObject()
                .put(OfflineKeys.ID, it.id)
                .put(OfflineKeys.TITLE, it.title)
                .put(OfflineKeys.ARTIST, it.artist)
                .put(OfflineKeys.ALBUM, it.album)
                .put(OfflineKeys.DURATION_MS, it.durationMs)
                .put(OfflineKeys.MIME_TYPE, it.mimeType)
                .put("size", it.sizeBytes)
                .put("has_art", it.artworkPath != null)
            }
          ),
        )
    val tmp = File(dir, "$INDEX_FILE.tmp")
    tmp.writeText(json.toString())
    tmp.renameTo(indexFile)
  }

  private fun load() {
    synchronized(lock) {
      try {
        if (!indexFile.exists()) return
        val json = JSONObject(indexFile.readText())
        order = json.getJSONArray("order").let { a -> (0 until a.length()).map { a.getString(it) } }
        val songs = json.getJSONArray("songs")
        for (i in 0 until songs.length()) {
          val o = songs.getJSONObject(i)
          val id = o.getString(OfflineKeys.ID)
          val audio = File(dir, "$id.audio")
          if (!isValidId(id) || !audio.exists()) continue
          val art = File(dir, "$id.jpg")
          byId[id] =
            OfflineSong(
              id = id,
              title = o.optString(OfflineKeys.TITLE),
              artist = o.optString(OfflineKeys.ARTIST),
              album = o.optString(OfflineKeys.ALBUM),
              durationMs = o.optLong(OfflineKeys.DURATION_MS),
              mimeType = o.optString(OfflineKeys.MIME_TYPE),
              audioPath = audio.absolutePath,
              artworkPath = art.takeIf { o.optBoolean("has_art") && it.exists() }?.absolutePath,
              sizeBytes = o.optLong("size"),
            )
        }
        publish()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to read offline index, starting empty", e)
      }
    }
  }

  private fun removeFiles(id: String) {
    File(dir, "$id.audio").delete()
    File(dir, "$id.jpg").delete()
  }

  /** Deletes files left behind by interrupted transfers or lost index entries. */
  private fun cleanOrphans() {
    val known = byId.keys
    dir.listFiles()?.forEach { f ->
      val id = f.name.substringBefore('.')
      if (f.name == INDEX_FILE) return@forEach
      if (f.name.endsWith(".tmp") || id !in known) f.delete()
    }
  }

  // endregion

  private fun copyExactly(from: InputStream, to: java.io.OutputStream, length: Long) {
    val buffer = ByteArray(32 * 1024)
    var remaining = length
    while (remaining > 0) {
      val read = from.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
      if (read < 0) throw IOException("Transfer ended $remaining bytes early")
      to.write(buffer, 0, read)
      remaining -= read
    }
  }

  /** Ids come from the phone; only allow what a YouTube id looks like so they can't escape [dir]. */
  private fun isValidId(id: String) = ID_REGEX.matches(id)

  companion object {
    private const val TAG = "OfflineLibrary"
    private const val PREFS = "offline_library"
    private const val KEY_LIMIT = "limit"
    private const val INDEX_FILE = "index.json"
    const val DEFAULT_LIMIT = 25
    private const val MIN_FREE_BYTES = 100L * 1024 * 1024
    private const val RESPONSE_TIMEOUT_MS = 20_000L
    private val ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")

    @Volatile private var instance: OfflineLibrary? = null

    fun get(context: Context): OfflineLibrary =
      instance
        ?: synchronized(this) { instance ?: OfflineLibrary(context).also { instance = it } }
  }
}
