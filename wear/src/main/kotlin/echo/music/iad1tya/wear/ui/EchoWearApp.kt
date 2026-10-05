package echo.music.iad1tya.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import echo.music.iad1tya.wear.data.PhoneConnection

private const val ROUTE_PLAYER = "player"
private const val ROUTE_QUEUE = "queue"
private const val ROUTE_LIBRARY = "library"

@Composable
fun EchoWearApp(isAmbient: Boolean, viewModel: PlayerViewModel = viewModel()) {
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_START -> viewModel.onStart()
        Lifecycle.Event.ON_STOP -> viewModel.onStop()
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val ui by viewModel.ui.collectAsStateWithLifecycle()
  val navController = rememberSwipeDismissableNavController()
  val openLibrary = { navController.navigate(ROUTE_LIBRARY) }

  AppScaffold {
    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_PLAYER) {
      composable(ROUTE_PLAYER) {
        when (val current = ui) {
          is MainUi.Status ->
            StatusScreen(
              kind =
                when (current.connection) {
                  PhoneConnection.Connecting -> StatusKind.Connecting
                  PhoneConnection.Disconnected -> StatusKind.Disconnected
                  else -> StatusKind.NothingPlaying
                },
              onOpenLibrary = openLibrary,
            )
          is MainUi.Player ->
            PlayerPages(
              state = current.state,
              isAmbient = isAmbient,
              onPlayPause = viewModel::playPause,
              onNext = viewModel::next,
              onPrevious = viewModel::previous,
              onToggleLike = viewModel::toggleLike,
              onToggleShuffle = viewModel::toggleShuffle,
              onToggleRepeat = viewModel::toggleRepeat,
              onVolumeSteps = viewModel::volumeStep,
              onOpenQueue = { navController.navigate(ROUTE_QUEUE) },
              onOpenLibrary = openLibrary,
            )
        }
      }
      composable(ROUTE_QUEUE) {
        val state = (ui as? MainUi.Player)?.state
        QueueScreen(
          queue = state?.queue.orEmpty(),
          onSelect = { entry ->
            viewModel.playQueueItem(entry.index)
            navController.popBackStack()
          },
        )
      }
      composable(ROUTE_LIBRARY) {
        val songs by viewModel.library.songs.collectAsStateWithLifecycle()
        val status by viewModel.library.status.collectAsStateWithLifecycle()
        val limit by viewModel.library.limit.collectAsStateWithLifecycle()
        val reachable by viewModel.phoneReachable.collectAsStateWithLifecycle()
        LibraryScreen(
          songs = songs,
          status = status,
          limit = limit,
          totalSizeBytes = viewModel.library.totalSizeBytes,
          phoneReachable = reachable,
          onSync = viewModel::syncOffline,
          onSetLimit = viewModel.library::setLimit,
          onPlay = { index ->
            viewModel.playOffline(songs, index)
            // Back to the player, which now shows the watch's own playback.
            navController.popBackStack(ROUTE_PLAYER, inclusive = false)
          },
        )
      }
    }
  }
}
