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

  val connection by viewModel.connection.collectAsStateWithLifecycle()
  val navController = rememberSwipeDismissableNavController()

  AppScaffold {
    SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_PLAYER) {
      composable(ROUTE_PLAYER) {
        when (val c = connection) {
          PhoneConnection.Connecting -> StatusScreen(StatusKind.Connecting)
          PhoneConnection.Disconnected -> StatusScreen(StatusKind.Disconnected)
          PhoneConnection.Idle -> StatusScreen(StatusKind.NothingPlaying)
          is PhoneConnection.Active ->
            PlayerPages(
              state = c.state,
              isAmbient = isAmbient,
              onPlayPause = viewModel::playPause,
              onNext = viewModel::next,
              onPrevious = viewModel::previous,
              onToggleLike = viewModel::toggleLike,
              onToggleShuffle = viewModel::toggleShuffle,
              onToggleRepeat = viewModel::toggleRepeat,
              onVolumeSteps = viewModel::volumeStep,
              onOpenQueue = { navController.navigate(ROUTE_QUEUE) },
            )
        }
      }
      composable(ROUTE_QUEUE) {
        val state = (connection as? PhoneConnection.Active)?.state
        QueueScreen(
          queue = state?.queue.orEmpty(),
          onSelect = { entry ->
            viewModel.playQueueItem(entry.index)
            navController.popBackStack()
          },
        )
      }
    }
  }
}
