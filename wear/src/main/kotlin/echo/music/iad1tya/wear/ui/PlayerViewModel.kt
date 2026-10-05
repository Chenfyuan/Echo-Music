package echo.music.iad1tya.wear.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import echo.music.iad1tya.wear.data.PhoneConnection
import echo.music.iad1tya.wear.data.PhoneRepository
import kotlinx.coroutines.flow.StateFlow

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = PhoneRepository(application)

  val connection: StateFlow<PhoneConnection> = repository.connection

  /** Call when the UI becomes visible. */
  fun onStart() = repository.register()

  /** Call when the UI is no longer visible, so a sleeping watch isn't kept listening. */
  fun onStop() = repository.unregister()

  fun playPause() = repository.playPause()

  fun next() = repository.next()

  fun previous() = repository.previous()

  fun toggleLike() = repository.toggleLike()

  fun toggleShuffle() = repository.toggleShuffle()

  fun toggleRepeat() = repository.toggleRepeat()

  fun volumeStep(steps: Int) = repository.volumeStep(steps)

  fun playQueueItem(index: Int) = repository.playQueueItem(index)

  override fun onCleared() {
    repository.unregister()
  }
}
