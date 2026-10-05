package echo.music.iad1tya.wear.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.SystemClock
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.data.OfflineSong
import echo.music.iad1tya.wear.data.PlayerState
import echo.music.iad1tya.wear.data.QueueEntry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * UI-side handle on [LocalPlaybackService]: turns the media session into the same [PlayerState]
 * the phone remote uses, so the player screens work for both sources.
 */
class LocalPlayer(context: Context) : Player.Listener {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val audioManager: AudioManager? = appContext.getSystemService()

  private var controller: MediaController? = null
  private var pending: Deferred<MediaController>? = null

  private var artworkId: String? = null
  private var artwork: Bitmap? = null

  private val _state = MutableStateFlow<PlayerState?>(null)

  /** `null` when nothing is loaded in the local player. */
  val state: StateFlow<PlayerState?> = _state.asStateFlow()

  /** Connects to the service (starting it if needed). Safe to call repeatedly. */
  fun connect() {
    scope.launch { awaitController() }
  }

  private suspend fun awaitController(): MediaController {
    controller?.let {
      return it
    }
    val deferred =
      pending
        ?: scope
          .async {
            val token =
              SessionToken(appContext, ComponentName(appContext, LocalPlaybackService::class.java))
            MediaController.Builder(appContext, token).buildAsync().await().also {
              it.addListener(this@LocalPlayer)
              controller = it
              pending = null
              refresh()
            }
          }
          .also { pending = it }
    return deferred.await()
  }

  /** Drops the connection (playback continues in the service). */
  fun disconnect() {
    controller?.removeListener(this)
    controller?.release()
    controller = null
  }

  // region commands

  fun playSongs(songs: List<OfflineSong>, startIndex: Int) {
    if (songs.isEmpty()) return
    val items = songs.map { it.toMediaItem() }
    scope.launch {
      val c = awaitController()
      c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
      c.prepare()
      c.play()
    }
  }

  fun playPause() {
    val c = controller ?: return
    if (c.playWhenReady) c.pause()
    else {
      if (c.playbackState == Player.STATE_IDLE) c.prepare()
      c.play()
    }
  }

  fun pause() {
    controller?.pause()
  }

  fun next() {
    controller?.seekToNext()
  }

  fun previous() {
    controller?.seekToPrevious()
  }

  fun toggleShuffle() {
    controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
  }

  fun toggleRepeat() {
    controller?.let {
      it.repeatMode =
        when (it.repeatMode) {
          Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
          Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
          else -> Player.REPEAT_MODE_OFF
        }
    }
  }

  fun playQueueItem(index: Int) {
    controller?.let {
      if (index in 0 until it.mediaItemCount) {
        it.seekTo(index, C.TIME_UNSET)
        it.play()
      }
    }
  }

  fun volumeStep(steps: Int) {
    val am = audioManager ?: return
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    am.setStreamVolume(
      AudioManager.STREAM_MUSIC,
      (am.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max),
      0,
    )
    refresh()
  }

  // endregion

  override fun onEvents(player: Player, events: Player.Events) = refresh()

  private fun refresh() {
    val c = controller
    if (c == null || c.mediaItemCount == 0) {
      _state.value = null
      return
    }
    val item = c.currentMediaItem
    if (item == null) {
      _state.value = null
      return
    }
    if (item.mediaId != artworkId) {
      artworkId = item.mediaId
      artwork =
        item.mediaMetadata.artworkUri?.path?.let { path ->
          runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }

    val queue = ArrayList<QueueEntry>()
    val timeline = c.currentTimeline
    val window = androidx.media3.common.Timeline.Window()
    var index = c.currentMediaItemIndex
    while (index != C.INDEX_UNSET && queue.size < WearProtocol.MAX_QUEUE_ITEMS) {
      val meta = timeline.getWindow(index, window).mediaItem.mediaMetadata
      queue += QueueEntry(index, meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty())
      index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
    }

    val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
    _state.value =
      PlayerState(
        mediaId = item.mediaId,
        title = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
        isPlaying = c.isPlaying,
        isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
        positionMs = c.currentPosition.coerceAtLeast(0L),
        durationMs = duration,
        receivedAtElapsedMs = SystemClock.elapsedRealtime(),
        shuffle = c.shuffleModeEnabled,
        repeatMode =
          when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> WearProtocol.REPEAT_ONE
            Player.REPEAT_MODE_ALL -> WearProtocol.REPEAT_ALL
            else -> WearProtocol.REPEAT_OFF
          },
        volume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
        volumeMax = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0,
        artwork = artwork,
        queue = queue,
        canLike = false,
      )
  }

  private fun OfflineSong.toMediaItem(): MediaItem =
    MediaItem.Builder()
      .setMediaId(id)
      .setUri(File(audioPath).toUri())
      .apply { if (mimeType.isNotBlank()) setMimeType(mimeType) }
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setTitle(title)
          .setArtist(artist)
          .setAlbumTitle(album.ifBlank { null })
          .apply { artworkPath?.let { setArtworkUri(File(it).toUri()) } }
          .build()
      )
      .build()
}
