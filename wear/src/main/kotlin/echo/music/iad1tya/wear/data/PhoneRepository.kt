package echo.music.iad1tya.wear.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.StateKeys
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Talks to the Echo Music phone app over the Wearable Data Layer.
 *
 * - Playback state arrives as a Data Item ([WearProtocol.PATH_STATE]) the phone keeps up to date.
 * - Controls are sent as Messages ([WearProtocol.PATH_COMMAND_PREFIX]) to the nearest phone node.
 *
 * [connection] is only kept fresh between [register] and [unregister]; the UI ties those to the
 * lifecycle so a sleeping watch doesn't keep listening.
 */
class PhoneRepository(context: Context) {
  private val appContext = context.applicationContext
  private val dataClient = Wearable.getDataClient(appContext)
  private val messageClient = Wearable.getMessageClient(appContext)
  private val capabilityClient = Wearable.getCapabilityClient(appContext)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _connection = MutableStateFlow<PhoneConnection>(PhoneConnection.Connecting)
  val connection: StateFlow<PhoneConnection> = _connection.asStateFlow()

  @Volatile private var phoneNodes: Set<Node> = emptySet()
  @Volatile private var lastState: PlayerState? = null
  @Volatile private var lastArtworkMediaId: String? = null
  @Volatile private var lastArtwork: Bitmap? = null

  private val dataListener = com.google.android.gms.wearable.DataClient.OnDataChangedListener { events ->
    handleDataEvents(events)
  }

  private val capabilityListener =
    CapabilityClient.OnCapabilityChangedListener { info ->
      updateNodes(info.nodes)
    }

  /** Starts listening and asks the phone for a fresh state. Safe to call repeatedly. */
  fun register() {
    dataClient.addListener(dataListener)
    capabilityClient.addListener(capabilityListener, WearProtocol.CAPABILITY_PHONE)
    scope.launch {
      try {
        val info =
          capabilityClient
            .getCapability(WearProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
            .await()
        updateNodes(info.nodes)
        loadExistingState()
        send(WearProtocol.Command.REQUEST_STATE)
      } catch (e: Exception) {
        Log.w(TAG, "Initial phone lookup failed", e)
        updateNodes(emptySet())
      }
    }
  }

  fun unregister() {
    dataClient.removeListener(dataListener)
    capabilityClient.removeListener(capabilityListener)
  }

  // region commands

  fun playPause() = send(WearProtocol.Command.PLAY_PAUSE)

  fun next() = send(WearProtocol.Command.NEXT)

  fun previous() = send(WearProtocol.Command.PREVIOUS)

  fun toggleLike() = send(WearProtocol.Command.TOGGLE_LIKE)

  fun toggleShuffle() = send(WearProtocol.Command.TOGGLE_SHUFFLE)

  fun toggleRepeat() = send(WearProtocol.Command.TOGGLE_REPEAT)

  fun volumeStep(steps: Int) =
    send(WearProtocol.Command.VOLUME_STEP, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(steps).array())

  fun playQueueItem(index: Int) =
    send(WearProtocol.Command.PLAY_QUEUE_ITEM, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(index).array())

  /** The phone node commands go to, nearest first; `null` if the phone isn't reachable. */
  val phoneNodeId: String?
    get() = phoneNodes.minByOrNull { if (it.isNearby) 0 else 1 }?.id

  private fun send(command: String, payload: ByteArray = ByteArray(0)) {
    val nodeId = phoneNodeId ?: return
    scope.launch {
      try {
        messageClient.sendMessage(nodeId, WearProtocol.commandPath(command), payload).await()
      } catch (e: Exception) {
        Log.w(TAG, "Failed to send $command", e)
      }
    }
  }

  // endregion

  // region incoming state

  private fun updateNodes(nodes: Set<Node>) {
    phoneNodes = nodes
    publish()
  }

  private suspend fun loadExistingState() {
    val uri = Uri.Builder().scheme("wear").path(WearProtocol.PATH_STATE).build()
    val items = dataClient.getDataItems(uri).await()
    try {
      items.forEach { applyDataItem(it) }
    } finally {
      items.release()
    }
    publish()
  }

  private fun handleDataEvents(events: DataEventBuffer) {
    val items =
      events
        .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.PATH_STATE }
        .map { it.dataItem.freeze() }
    if (items.isEmpty()) return
    scope.launch {
      items.forEach { applyDataItem(it) }
      publish()
    }
  }

  private suspend fun applyDataItem(item: DataItem) {
    if (item.uri.path != WearProtocol.PATH_STATE) return
    val map = DataMapItem.fromDataItem(item).dataMap
    lastState = parseState(map)
  }

  private suspend fun parseState(map: DataMap): PlayerState? {
    if (!map.getBoolean(StateKeys.HAS_MEDIA, false)) return null
    val mediaId = map.getString(StateKeys.MEDIA_ID, "")
    if (mediaId != lastArtworkMediaId) {
      lastArtwork = map.getAsset(StateKeys.ARTWORK)?.let { loadArtwork(it) }
      lastArtworkMediaId = mediaId
    } else if (lastArtwork == null) {
      // Previous attempt failed (asset not synced yet) – retry.
      lastArtwork = map.getAsset(StateKeys.ARTWORK)?.let { loadArtwork(it) }
    }

    val titles = map.getStringArray(StateKeys.QUEUE_TITLES).orEmpty()
    val artists = map.getStringArray(StateKeys.QUEUE_ARTISTS).orEmpty()
    val indices = map.getIntegerArrayList(StateKeys.QUEUE_INDICES).orEmpty()
    val queue =
      titles.indices.mapNotNull { i ->
        val index = indices.getOrNull(i) ?: return@mapNotNull null
        QueueEntry(index = index, title = titles[i], artist = artists.getOrElse(i) { "" })
      }

    return PlayerState(
      mediaId = mediaId,
      title = map.getString(StateKeys.TITLE, ""),
      artist = map.getString(StateKeys.ARTIST, ""),
      album = map.getString(StateKeys.ALBUM, ""),
      isPlaying = map.getBoolean(StateKeys.IS_PLAYING, false),
      isBuffering = map.getBoolean(StateKeys.IS_BUFFERING, false),
      positionMs = map.getLong(StateKeys.POSITION_MS, 0L),
      durationMs = map.getLong(StateKeys.DURATION_MS, 0L),
      receivedAtElapsedMs = SystemClock.elapsedRealtime(),
      liked = map.getBoolean(StateKeys.LIKED, false),
      shuffle = map.getBoolean(StateKeys.SHUFFLE, false),
      repeatMode = map.getInt(StateKeys.REPEAT_MODE, 0),
      volume = map.getInt(StateKeys.VOLUME, 0),
      volumeMax = map.getInt(StateKeys.VOLUME_MAX, 0),
      artwork = lastArtwork,
      queue = queue,
    )
  }

  private suspend fun loadArtwork(asset: com.google.android.gms.wearable.Asset): Bitmap? =
    try {
      val response = dataClient.getFdForAsset(asset).await()
      withContext(Dispatchers.IO) {
        response.inputStream.use { BitmapFactory.decodeStream(it) }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to load artwork", e)
      null
    }

  private fun publish() {
    val state = lastState
    _connection.update {
      when {
        phoneNodes.isEmpty() -> PhoneConnection.Disconnected
        state == null -> PhoneConnection.Idle
        else -> PhoneConnection.Active(state)
      }
    }
  }

  // endregion

  private companion object {
    const val TAG = "PhoneRepository"
  }
}
