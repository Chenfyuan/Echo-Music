package echo.music.iad1tya.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import echo.music.iad1tya.wear.data.OfflineLibrary
import echo.music.iad1tya.wear.data.OfflineSong
import echo.music.iad1tya.wear.data.PhoneConnection
import echo.music.iad1tya.wear.data.PhoneRepository
import echo.music.iad1tya.wear.data.PlayerState
import echo.music.iad1tya.wear.playback.LocalPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the main screen shows. */
sealed interface MainUi {
  /** Nothing to control: still connecting, no phone, or phone idle and no local playback. */
  data class Status(val connection: PhoneConnection) : MainUi

  /** A player, either remote-controlling the phone or playing offline songs on the watch. */
  data class Player(val state: PlayerState, val isLocal: Boolean) : MainUi
}

private enum class Source {
  Phone,
  Watch,
}

/**
 * Owns both playback sources and routes controls to whichever one is active:
 * - **Phone**: the watch is a remote for the phone's player ([PhoneRepository]).
 * - **Watch**: the watch plays synced offline songs itself ([LocalPlayer]).
 *
 * Only one source should make sound at a time, so starting one pauses the other.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
  private val phone = PhoneRepository(application)
  private val local = LocalPlayer(application)
  val library: OfflineLibrary = OfflineLibrary.get(application)

  private val preferred = MutableStateFlow(Source.Phone)
  @Volatile private var activeIsLocal = false

  val phoneConnection: StateFlow<PhoneConnection> = phone.connection

  /** Whether a phone running Echo Music is reachable right now (needed to sync). */
  val phoneReachable: StateFlow<Boolean> =
    phone.connection
      .map { it is PhoneConnection.Idle || it is PhoneConnection.Active }
      .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val ui: StateFlow<MainUi> =
    combine(phone.connection, local.state, preferred) { connection, localState, pref ->
        val remote = (connection as? PhoneConnection.Active)?.state
        val localPlaying = localState?.isPlaying == true
        val remotePlaying = remote?.isPlaying == true
        val source =
          when {
            remotePlaying && localPlaying -> pref
            remotePlaying -> Source.Phone
            localPlaying -> Source.Watch
            pref == Source.Watch && localState != null -> Source.Watch
            remote != null -> Source.Phone
            localState != null -> Source.Watch
            else -> null
          }
        activeIsLocal = source == Source.Watch
        when (source) {
          Source.Phone -> MainUi.Player(remote!!, isLocal = false)
          Source.Watch -> MainUi.Player(localState!!, isLocal = true)
          null -> MainUi.Status(connection)
        }
      }
      .stateIn(viewModelScope, SharingStarted.Eagerly, MainUi.Status(PhoneConnection.Connecting))

  init {
    // The user started something on the phone: stop the watch's own playback.
    viewModelScope.launch {
      phone.connection
        .map { (it as? PhoneConnection.Active)?.state?.isPlaying == true }
        .distinctUntilChanged()
        .filter { it }
        .collect {
          if (local.state.value?.isPlaying == true) local.pause()
          preferred.value = Source.Phone
        }
    }
  }

  /** Call when the UI becomes visible. */
  fun onStart() {
    phone.register()
    local.connect()
  }

  /** Call when the UI is no longer visible, so a sleeping watch isn't kept listening. */
  fun onStop() {
    phone.unregister()
    local.disconnect()
  }

  // region controls (routed to the active source)

  fun playPause() = if (activeIsLocal) local.playPause() else phone.playPause()

  fun next() = if (activeIsLocal) local.next() else phone.next()

  fun previous() = if (activeIsLocal) local.previous() else phone.previous()

  fun toggleLike() = if (activeIsLocal) Unit else phone.toggleLike()

  fun toggleShuffle() = if (activeIsLocal) local.toggleShuffle() else phone.toggleShuffle()

  fun toggleRepeat() = if (activeIsLocal) local.toggleRepeat() else phone.toggleRepeat()

  fun volumeStep(steps: Int) = if (activeIsLocal) local.volumeStep(steps) else phone.volumeStep(steps)

  fun playQueueItem(index: Int) =
    if (activeIsLocal) local.playQueueItem(index) else phone.playQueueItem(index)

  // endregion

  // region offline

  /** Plays [songs] starting at [startIndex] on the watch, silencing the phone if it is playing. */
  fun playOffline(songs: List<OfflineSong>, startIndex: Int) {
    if ((phone.connection.value as? PhoneConnection.Active)?.state?.isPlaying == true) {
      phone.playPause()
    }
    preferred.value = Source.Watch
    local.playSongs(songs, startIndex)
  }

  fun syncOffline() {
    if (!phoneReachable.value) return
    library.onRequested()
    phone.requestOfflineSync(library.limit.value, library.haveIds())
  }

  // endregion

  override fun onCleared() {
    phone.unregister()
    local.disconnect()
  }
}
