package echo.music.iad1tya.wearsync

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.core.content.getSystemService
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import echo.music.iad1tya.extensions.metadata
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.Command
import echo.music.iad1tya.wear.WearProtocol.StateKeys
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Bridges the running [Player] to the Echo Music Wear OS app.
 *
 * Owned by [echo.music.iad1tya.playback.MusicService], so it lives exactly as long as the player:
 * - publishes the now-playing state (track, art, position, queue, toggles, volume) as a Data Layer
 *   item, debounced so bursts of player events collapse into one update, and only while a paired
 *   watch with the Echo Music app is reachable;
 * - executes the watch's control messages ([WearProtocol.Command]) against the player.
 *
 * All [player] access happens on the main thread (the Data Layer delivers messages there and
 * [scope] is main-bound), which is what ExoPlayer requires.
 */
class WearSyncManager(
  context: Context,
  private val player: Player,
  private val scope: CoroutineScope,
  private val isLiked: () -> Boolean,
  private val toggleLike: () -> Unit,
  private val likedChanges: Flow<Boolean>,
) : Player.Listener, MessageClient.OnMessageReceivedListener {
  private val appContext = context.applicationContext
  private val audioManager: AudioManager? = appContext.getSystemService()
  private val dataClient = Wearable.getDataClient(appContext)
  private val messageClient = Wearable.getMessageClient(appContext)
  private val capabilityClient = Wearable.getCapabilityClient(appContext)

  private val syncRequests = Channel<Unit>(Channel.CONFLATED)
  private var syncJob: Job? = null
  private var likedJob: Job? = null

  @Volatile private var hasWatch = false
  private var started = false

  private var artworkMediaId: String? = null
  private var artworkAsset: Asset? = null

  private val capabilityListener =
    CapabilityClient.OnCapabilityChangedListener { info -> onWatchCapability(info) }

  fun start() {
    if (started) return
    started = true
    player.addListener(this)
    messageClient.addListener(this)
    capabilityClient.addListener(capabilityListener, WearProtocol.CAPABILITY_WATCH)

    syncJob =
      scope.launch {
        for (request in syncRequests) {
          // Collapse bursts (transition + play state + timeline fire together) into one push.
          delay(SYNC_DEBOUNCE_MS)
          syncRequests.tryReceive()
          if (hasWatch) pushState()
        }
      }
    likedJob = scope.launch { likedChanges.distinctUntilChanged().collect { requestSync() } }

    scope.launch {
      try {
        val info =
          capabilityClient
            .getCapability(WearProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_ALL)
            .await()
        onWatchCapability(info)
      } catch (e: Exception) {
        // No Play services / no Wearable API: the bridge simply stays inactive.
        Timber.tag(TAG).d(e, "Wearable capability lookup failed")
      }
    }
  }

  fun stop() {
    if (!started) return
    started = false
    player.removeListener(this)
    messageClient.removeListener(this)
    capabilityClient.removeListener(capabilityListener)
    syncJob?.cancel()
    likedJob?.cancel()
    // Tell the watch that nothing is playing any more rather than leaving stale controls behind.
    if (hasWatch) {
      try {
        val request = PutDataMapRequest.create(WearProtocol.PATH_STATE).setUrgent()
        request.dataMap.putBoolean(StateKeys.HAS_MEDIA, false)
        request.dataMap.putLong(StateKeys.UPDATED_AT, System.currentTimeMillis())
        dataClient.putDataItem(request.asPutDataRequest())
      } catch (e: Exception) {
        Timber.tag(TAG).d(e, "Failed to publish idle state")
      }
    }
  }

  private fun onWatchCapability(info: CapabilityInfo) {
    val present = info.nodes.isNotEmpty()
    val appeared = present && !hasWatch
    hasWatch = present
    if (appeared) requestSync()
  }

  private fun requestSync() {
    syncRequests.trySend(Unit)
  }

  // region Player.Listener

  override fun onEvents(player: Player, events: Player.Events) {
    if (
      events.containsAny(
        Player.EVENT_MEDIA_ITEM_TRANSITION,
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED,
        Player.EVENT_IS_PLAYING_CHANGED,
        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        Player.EVENT_REPEAT_MODE_CHANGED,
        Player.EVENT_POSITION_DISCONTINUITY,
        Player.EVENT_TIMELINE_CHANGED,
        Player.EVENT_MEDIA_METADATA_CHANGED,
      )
    ) {
      requestSync()
    }
  }

  // endregion

  // region commands from the watch

  override fun onMessageReceived(event: MessageEvent) {
    val command = WearProtocol.commandFrom(event.path) ?: return
    try {
      handleCommand(command, event.data)
    } catch (e: Exception) {
      Timber.tag(TAG).w(e, "Failed to handle watch command %s", command)
    }
  }

  private fun handleCommand(command: String, payload: ByteArray) {
    when (command) {
      Command.PLAY_PAUSE -> {
        if (player.playWhenReady) {
          player.pause()
        } else {
          if (player.playbackState == Player.STATE_IDLE) player.prepare()
          player.play()
        }
      }
      Command.NEXT -> player.seekToNext()
      Command.PREVIOUS -> player.seekToPrevious()
      Command.TOGGLE_LIKE -> toggleLike()
      Command.TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
      Command.TOGGLE_REPEAT ->
        player.repeatMode =
          when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
          }
      Command.VOLUME_STEP -> {
        if (payload.size >= Int.SIZE_BYTES) stepVolume(ByteBuffer.wrap(payload).int)
      }
      Command.PLAY_QUEUE_ITEM -> {
        if (payload.size >= Int.SIZE_BYTES) {
          val index = ByteBuffer.wrap(payload).int
          if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, C.TIME_UNSET)
            player.play()
          }
        }
      }
      Command.REQUEST_STATE -> {
        hasWatch = true
        requestSync()
      }
    }
  }

  private fun stepVolume(steps: Int) {
    val am = audioManager ?: return
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val target = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    requestSync()
  }

  // endregion

  // region state publishing

  private suspend fun pushState() {
    try {
      val item = player.currentMediaItem
      val request = PutDataMapRequest.create(WearProtocol.PATH_STATE).setUrgent()
      val map = request.dataMap

      if (item == null) {
        map.putBoolean(StateKeys.HAS_MEDIA, false)
      } else {
        val meta = item.metadata
        val mediaId = item.mediaId
        val title = meta?.title ?: item.mediaMetadata.title?.toString().orEmpty()
        val artist =
          meta?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() }
            ?: item.mediaMetadata.artist?.toString().orEmpty()
        val durationMs =
          player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: ((meta?.duration ?: 0) * 1000L)

        map.putBoolean(StateKeys.HAS_MEDIA, true)
        map.putString(StateKeys.MEDIA_ID, mediaId)
        map.putString(StateKeys.TITLE, title)
        map.putString(StateKeys.ARTIST, artist)
        map.putString(StateKeys.ALBUM, meta?.album?.title.orEmpty())
        map.putBoolean(StateKeys.IS_PLAYING, player.isPlaying)
        map.putBoolean(
          StateKeys.IS_BUFFERING,
          player.playbackState == Player.STATE_BUFFERING && player.playWhenReady,
        )
        map.putLong(StateKeys.POSITION_MS, player.currentPosition.coerceAtLeast(0L))
        map.putLong(StateKeys.DURATION_MS, durationMs)
        map.putBoolean(StateKeys.LIKED, isLiked())
        map.putBoolean(StateKeys.SHUFFLE, player.shuffleModeEnabled)
        map.putInt(StateKeys.REPEAT_MODE, player.repeatMode.toWireRepeatMode())
        audioManager?.let {
          map.putInt(StateKeys.VOLUME, it.getStreamVolume(AudioManager.STREAM_MUSIC))
          map.putInt(StateKeys.VOLUME_MAX, it.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        }
        putQueue(map)
        artworkFor(mediaId, meta?.thumbnailUrl ?: item.mediaMetadata.artworkUri?.toString())
          ?.let { map.putAsset(StateKeys.ARTWORK, it) }
      }
      map.putLong(StateKeys.UPDATED_AT, System.currentTimeMillis())
      dataClient.putDataItem(request.asPutDataRequest()).await()
    } catch (e: Exception) {
      // Wearable API unavailable (no Play services) or the watch went away: not worth surfacing.
      Timber.tag(TAG).d(e, "Failed to publish state to watch")
    }
  }

  /** Upcoming tracks in actual playback order (respects shuffle), current track first. */
  private fun putQueue(map: com.google.android.gms.wearable.DataMap) {
    val timeline = player.currentTimeline
    val titles = ArrayList<String>()
    val artists = ArrayList<String>()
    val indices = ArrayList<Int>()
    var index = player.currentMediaItemIndex
    val window = androidx.media3.common.Timeline.Window()
    while (index != C.INDEX_UNSET && titles.size < WearProtocol.MAX_QUEUE_ITEMS) {
      val item: MediaItem = timeline.getWindow(index, window).mediaItem
      val meta = item.metadata
      titles += meta?.title ?: item.mediaMetadata.title?.toString().orEmpty()
      artists +=
        meta?.artists?.joinToString { it.name }
          ?: item.mediaMetadata.artist?.toString().orEmpty()
      indices += index
      index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
    }
    map.putStringArray(StateKeys.QUEUE_TITLES, titles.toTypedArray())
    map.putStringArray(StateKeys.QUEUE_ARTISTS, artists.toTypedArray())
    map.putIntegerArrayList(StateKeys.QUEUE_INDICES, indices)
  }

  /** Small JPEG of the current track's art, cached per track so it's only fetched once. */
  private suspend fun artworkFor(mediaId: String, url: String?): Asset? {
    if (mediaId == artworkMediaId) return artworkAsset
    artworkMediaId = mediaId
    artworkAsset = null
    if (url.isNullOrBlank()) return null
    artworkAsset =
      try {
        withContext(Dispatchers.IO) {
          val request =
            ImageRequest.Builder(appContext)
              .data(url)
              .size(WearProtocol.ARTWORK_SIZE_PX)
              .allowHardware(false)
              .build()
          val result = appContext.imageLoader.execute(request) as? SuccessResult
          result?.image?.toBitmap()?.toJpegAsset()
        }
      } catch (e: Exception) {
        Timber.tag(TAG).d(e, "Failed to load watch artwork")
        null
      }
    return artworkAsset
  }

  private fun Bitmap.toJpegAsset(): Asset {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
    return Asset.createFromBytes(out.toByteArray())
  }

  private fun Int.toWireRepeatMode(): Int =
    when (this) {
      Player.REPEAT_MODE_ONE -> WearProtocol.REPEAT_ONE
      Player.REPEAT_MODE_ALL -> WearProtocol.REPEAT_ALL
      else -> WearProtocol.REPEAT_OFF
    }

  // endregion

  private companion object {
    const val TAG = "WearSyncManager"
    const val SYNC_DEBOUNCE_MS = 250L
    const val JPEG_QUALITY = 80
  }
}
