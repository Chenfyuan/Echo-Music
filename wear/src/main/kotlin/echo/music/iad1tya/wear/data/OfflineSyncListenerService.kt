package echo.music.iad1tya.wear.data

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.OfflineKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives the phone's offline-sync traffic even when the watch UI isn't open: the manifest and
 * "done" messages, and one channel per song.
 *
 * Channel callbacks run on a background thread, so the copy is done synchronously here: that keeps
 * this service (and the process) alive for the duration of the transfer.
 */
class OfflineSyncListenerService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    val library = OfflineLibrary.get(this)
    when (event.path) {
      WearProtocol.PATH_OFFLINE_MANIFEST -> {
        try {
          val json = JSONObject(String(event.data, Charsets.UTF_8))
          library.applyManifest(
            ids = json.getJSONArray(OfflineKeys.IDS).toList(),
            missing = json.getJSONArray(OfflineKeys.MISSING).toList(),
          )
        } catch (e: Exception) {
          Log.w(TAG, "Bad offline manifest", e)
        }
      }
      WearProtocol.PATH_OFFLINE_DONE -> library.onDone()
    }
  }

  override fun onChannelOpened(channel: ChannelClient.Channel) {
    if (channel.path != WearProtocol.PATH_OFFLINE_SONG_CHANNEL) return
    val client = Wearable.getChannelClient(this)
    try {
      Tasks.await(client.getInputStream(channel)).use { OfflineLibrary.get(this).receiveSong(it) }
    } catch (e: Exception) {
      Log.w(TAG, "Failed to receive song", e)
    } finally {
      runCatching { Tasks.await(client.close(channel)) }
    }
  }

  private fun JSONArray.toList(): List<String> = (0 until length()).map { getString(it) }

  private companion object {
    const val TAG = "OfflineSyncService"
  }
}
