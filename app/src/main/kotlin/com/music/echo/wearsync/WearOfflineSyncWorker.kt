package echo.music.iad1tya.wearsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.SongSortType
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.Song
import echo.music.iad1tya.di.DownloadCache
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.OfflineKeys
import java.io.DataOutputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WearOfflineEntryPoint {
  fun database(): MusicDatabase

  @DownloadCache fun downloadCache(): SimpleCache
}

/**
 * Sends the phone's downloaded songs to the watch so it can play them without the phone.
 *
 * Picks the most recently downloaded songs (up to the limit the watch asked for) whose audio is
 * fully in the download cache, tells the watch the resulting list (so it can drop everything else),
 * then streams each song the watch is missing over a Data Layer channel. Audio bytes are copied
 * as-is from the cache: no transcoding.
 */
class WearOfflineSyncWorker(private val context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  private val entryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WearOfflineEntryPoint::class.java)
  private val database = entryPoint.database()
  private val downloadCache = entryPoint.downloadCache()

  override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val nodeId = inputData.getString(KEY_NODE_ID) ?: return@withContext Result.failure()
    val request = runCatching { JSONObject(inputData.getString(KEY_REQUEST).orEmpty()) }.getOrNull()
    val limit =
      (request?.optInt(OfflineKeys.LIMIT, DEFAULT_LIMIT) ?: DEFAULT_LIMIT)
        .coerceIn(WearProtocol.OFFLINE_MIN_LIMIT, WearProtocol.OFFLINE_MAX_LIMIT)
    val have = request?.optJSONArray(OfflineKeys.HAVE).toStringSet()

    val messageClient = Wearable.getMessageClient(context)
    val channelClient = Wearable.getChannelClient(context)
    try {
      setForeground(foregroundInfo(0, 0))
    } catch (e: Exception) {
      Timber.tag(TAG).d(e, "Could not promote to foreground")
    }

    try {
      val songs = selectSongs(limit)
      val missing = songs.filter { it.id !in have }
      messageClient
        .sendMessage(
          nodeId,
          WearProtocol.PATH_OFFLINE_MANIFEST,
          JSONObject()
            .put(OfflineKeys.IDS, JSONArray(songs.map { it.id }))
            .put(OfflineKeys.MISSING, JSONArray(missing.map { it.id }))
            .toString()
            .toByteArray(Charsets.UTF_8),
        )
        .await()

      missing.forEachIndexed { index, song ->
        setForegroundSafely(index, missing.size)
        try {
          sendSong(channelClient, nodeId, song)
        } catch (e: Exception) {
          // One bad song (e.g. watch ran out of space and closed the channel) must not abort the rest.
          Timber.tag(TAG).w(e, "Failed to send %s to watch", song.id)
        }
      }
      Result.success()
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Offline sync to watch failed")
      Result.failure()
    } finally {
      runCatching {
        messageClient.sendMessage(nodeId, WearProtocol.PATH_OFFLINE_DONE, ByteArray(0)).await()
      }
    }
  }

  /** Newest downloads first, only those whose audio is complete in the download cache. */
  private suspend fun selectSongs(limit: Int): List<Song> =
    database
      .downloadedSongs(SongSortType.CREATE_DATE, descending = true)
      .first()
      .asSequence()
      .filter { !it.song.isLocal }
      .filter { cachedLength(it.id) > 0 }
      .take(limit)
      .toList()

  private fun cachedLength(songId: String): Long {
    val length =
      ContentMetadata.getContentLength(downloadCache.getContentMetadata(songId))
        .takeIf { it != C.LENGTH_UNSET.toLong() }
    return if (length != null && downloadCache.isCached(songId, 0, length)) length else -1L
  }

  private suspend fun sendSong(channelClient: ChannelClient, nodeId: String, song: Song) {
    val audioSize = cachedLength(song.id)
    if (audioSize <= 0) return
    val artwork = runCatching { loadWearArtworkJpeg(context, song.thumbnailUrl) }.getOrNull()
    val header =
      JSONObject()
        .put(OfflineKeys.ID, song.id)
        .put(OfflineKeys.TITLE, song.title)
        .put(OfflineKeys.ARTIST, song.artists.joinToString { it.name })
        .put(OfflineKeys.ALBUM, song.album?.title.orEmpty())
        .put(OfflineKeys.DURATION_MS, song.song.duration * 1000L)
        .put(OfflineKeys.MIME_TYPE, song.format?.mimeType.orEmpty())
        .put(OfflineKeys.AUDIO_SIZE, audioSize)
        .put(OfflineKeys.ARTWORK_SIZE, artwork?.size ?: 0)
        .toString()
        .toByteArray(Charsets.UTF_8)

    val channel = channelClient.openChannel(nodeId, WearProtocol.PATH_OFFLINE_SONG_CHANNEL).await()
    try {
      channelClient.getOutputStream(channel).await().use { raw ->
        val out = DataOutputStream(raw)
        out.writeInt(header.size)
        out.write(header)
        if (artwork != null) out.write(artwork)
        out.flush()
        copyFromCache(song.id, audioSize, raw)
        out.flush()
      }
    } finally {
      runCatching { channelClient.close(channel).await() }
    }
  }

  /** Streams the complete cached audio for [songId] into [out] without touching the network. */
  private fun copyFromCache(songId: String, length: Long, out: OutputStream) {
    val source = CacheDataSource.Factory().setCache(downloadCache).createDataSource()
    try {
      source.open(
        DataSpec.Builder()
          .setUri("https://cache.invalid/$songId".toUri())
          .setKey(songId)
          .setPosition(0)
          .setLength(length)
          .build()
      )
      val buffer = ByteArray(BUFFER_SIZE)
      var remaining = length
      while (remaining > 0) {
        val read = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) break
        out.write(buffer, 0, read)
        remaining -= read
      }
      if (remaining > 0) throw java.io.IOException("Cache ended early for $songId")
    } finally {
      source.close()
    }
  }

  private suspend fun setForegroundSafely(done: Int, total: Int) {
    try {
      setForeground(foregroundInfo(done, total))
    } catch (e: Exception) {
      Timber.tag(TAG).d(e, "Could not update foreground notification")
    }
  }

  private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
    val manager = context.getSystemService<NotificationManager>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      manager?.createNotificationChannel(
        NotificationChannel(
          CHANNEL_ID,
          context.getString(R.string.wear_sync_channel),
          NotificationManager.IMPORTANCE_LOW,
        )
      )
    }
    val notification =
      NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.echomusicnotification)
        .setContentTitle(context.getString(R.string.wear_sync_title))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(total, done, total == 0)
        .apply {
          if (total > 0) setContentText(context.getString(R.string.wear_sync_progress, done + 1, total))
        }
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
      ForegroundInfo(NOTIFICATION_ID, notification)
    }
  }

  private fun JSONArray?.toStringSet(): Set<String> =
    if (this == null) emptySet() else (0 until length()).mapTo(HashSet()) { getString(it) }

  companion object {
    const val UNIQUE_NAME = "wear_offline_sync"
    const val KEY_NODE_ID = "node_id"
    const val KEY_REQUEST = "request"
    private const val TAG = "WearOfflineSync"
    private const val CHANNEL_ID = "wear_offline_sync"
    private const val NOTIFICATION_ID = 7421
    private const val DEFAULT_LIMIT = 25
    private const val BUFFER_SIZE = 32 * 1024
  }
}
