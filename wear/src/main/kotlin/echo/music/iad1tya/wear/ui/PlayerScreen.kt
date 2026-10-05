package echo.music.iad1tya.wear.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.IconToggleButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import echo.music.iad1tya.wear.R
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.data.PlayerState
import kotlinx.coroutines.delay

/** Distance of rotary scrolling (in px) that equals one volume step on the phone. */
private const val ROTARY_PX_PER_VOLUME_STEP = 48f

/**
 * Now-playing pager: page 0 is the player, page 1 holds the toggles (like / shuffle / repeat).
 * Follows the Wear OS media layout: art-backed, title + artist, a bezel progress arc, one primary
 * control row and an [EdgeButton] to the queue.
 */
@Composable
fun PlayerPages(
  state: PlayerState,
  isAmbient: Boolean,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  onPrevious: () -> Unit,
  onToggleLike: () -> Unit,
  onToggleShuffle: () -> Unit,
  onToggleRepeat: () -> Unit,
  onVolumeSteps: (Int) -> Unit,
  onOpenQueue: () -> Unit,
  onOpenLibrary: () -> Unit,
) {
  if (isAmbient) {
    AmbientPlayer(state)
    return
  }

  val pagerState = rememberPagerState(pageCount = { 2 })
  HorizontalPagerScaffold(pagerState = pagerState) {
    HorizontalPager(state = pagerState) { page ->
      when (page) {
        0 ->
          PlayerPage(
            state = state,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onVolumeSteps = onVolumeSteps,
            onOpenQueue = onOpenQueue,
          )
        else ->
          OptionsPage(
            state = state,
            onToggleLike = onToggleLike,
            onToggleShuffle = onToggleShuffle,
            onToggleRepeat = onToggleRepeat,
            onOpenLibrary = onOpenLibrary,
          )
      }
    }
  }
}

@Composable
private fun PlayerPage(
  state: PlayerState,
  onPlayPause: () -> Unit,
  onNext: () -> Unit,
  onPrevious: () -> Unit,
  onVolumeSteps: (Int) -> Unit,
  onOpenQueue: () -> Unit,
) {
  // Ticks a frame clock only while visible and playing; progress is extrapolated between the
  // (infrequent) state updates from the phone.
  var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  LaunchedEffect(state.isPlaying, state.positionMs, state.receivedAtElapsedMs) {
    nowElapsed = SystemClock.elapsedRealtime()
    while (state.isPlaying) {
      delay(500)
      nowElapsed = SystemClock.elapsedRealtime()
    }
  }

  // Crown / bezel controls the phone's volume; show a short-lived HUD for feedback.
  val currentOnVolumeSteps by rememberUpdatedState(onVolumeSteps)
  var rotaryRemainder by remember { mutableFloatStateOf(0f) }
  var volumeHudVisibleUntil by remember { mutableLongStateOf(0L) }
  var hudVisible by remember { mutableStateOf(false) }
  LaunchedEffect(volumeHudVisibleUntil) {
    if (volumeHudVisibleUntil == 0L) return@LaunchedEffect
    hudVisible = true
    delay(1200)
    hudVisible = false
  }
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  ScreenScaffold(
    modifier =
      Modifier.onRotaryScrollEvent { event ->
          rotaryRemainder += event.verticalScrollPixels
          val steps = (rotaryRemainder / ROTARY_PX_PER_VOLUME_STEP).toInt()
          if (steps != 0) {
            rotaryRemainder -= steps * ROTARY_PX_PER_VOLUME_STEP
            // Rotating clockwise (positive pixels) should raise the volume.
            currentOnVolumeSteps(steps)
            volumeHudVisibleUntil = SystemClock.elapsedRealtime()
          }
          true
        }
        .focusRequester(focusRequester)
        .focusable(),
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      ArtworkBackground(state)

      CircularProgressIndicator(
        progress = { state.progressAt(nowElapsed) },
        modifier = Modifier.fillMaxSize().padding(2.dp),
        startAngle = 295f,
        endAngle = 245f,
        strokeWidth = 4.dp,
      )

      Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp).padding(top = 22.dp, bottom = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        TrackInfo(state)
        Row(
          modifier = Modifier.padding(top = 6.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = onPrevious,
            colors = IconButtonDefaults.filledVariantIconButtonColors(),
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_skip_previous),
              contentDescription = stringResource(R.string.previous),
            )
          }
          IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
          ) {
            Icon(
              painter =
                painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
              contentDescription =
                stringResource(if (state.isPlaying) R.string.pause else R.string.play),
            )
          }
          IconButton(
            onClick = onNext,
            colors = IconButtonDefaults.filledVariantIconButtonColors(),
            modifier = Modifier.size(IconButtonDefaults.SmallButtonSize),
          ) {
            Icon(
              painter = painterResource(R.drawable.ic_skip_next),
              contentDescription = stringResource(R.string.next),
            )
          }
        }
      }

      EdgeButton(
        onClick = onOpenQueue,
        modifier = Modifier.align(Alignment.BottomCenter),
        buttonSize = EdgeButtonSize.ExtraSmall,
      ) {
        Icon(painter = painterResource(R.drawable.ic_queue_music), contentDescription = null)
        Text(
          text = stringResource(R.string.up_next),
          modifier = Modifier.padding(start = 6.dp),
          maxLines = 1,
        )
      }

      AnimatedVisibility(
        visible = hudVisible,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        VolumeHud(state)
      }
    }
  }
}

@Composable
private fun TrackInfo(state: PlayerState) {
  Text(
    text = state.title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onBackground,
    maxLines = 1,
    modifier = Modifier.fillMaxWidth().basicMarquee(),
    textAlign = TextAlign.Center,
  )
  Text(
    text = state.artist.ifBlank { stringResource(R.string.unknown_artist) },
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.fillMaxWidth(),
    textAlign = TextAlign.Center,
  )
}

/** Album art, dimmed so text and controls stay legible; plain black when there is none. */
@Composable
private fun ArtworkBackground(state: PlayerState) {
  val artwork = state.artwork ?: return
  Image(
    bitmap = artwork.asImageBitmap(),
    contentDescription = null,
    contentScale = ContentScale.Crop,
    modifier =
      Modifier.fillMaxSize().alpha(0.4f).drawWithContent {
        drawContent()
        drawRect(
          Brush.verticalGradient(
            listOf(Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.85f))
          )
        )
      },
  )
}

@Composable
private fun VolumeHud(state: PlayerState) {
  val percent = if (state.volumeMax > 0) state.volume * 100 / state.volumeMax else 0
  Row(
    modifier =
      Modifier.clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Icon(
      painter = painterResource(R.drawable.ic_volume_up),
      contentDescription = null,
      modifier = Modifier.size(20.dp),
    )
    Text(
      text = stringResource(R.string.volume_percent, percent),
      style = MaterialTheme.typography.titleMedium,
    )
  }
}

@Composable
private fun OptionsPage(
  state: PlayerState,
  onToggleLike: () -> Unit,
  onToggleShuffle: () -> Unit,
  onToggleRepeat: () -> Unit,
  onOpenLibrary: () -> Unit,
) {
  ScreenScaffold {
    Column(
      modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.canLike) {
          val likeLabel = stringResource(if (state.liked) R.string.unlike else R.string.like)
          IconToggleButton(
            checked = state.liked,
            onCheckedChange = { onToggleLike() },
            modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
          ) {
            Icon(
              painter =
                painterResource(
                  if (state.liked) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                ),
              contentDescription = likeLabel,
            )
          }
        }
        val shuffleLabel =
          stringResource(if (state.shuffle) R.string.shuffle_on else R.string.shuffle_off)
        IconToggleButton(
          checked = state.shuffle,
          onCheckedChange = { onToggleShuffle() },
          modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
        ) {
          Icon(painter = painterResource(R.drawable.ic_shuffle), contentDescription = shuffleLabel)
        }
        val repeatLabel =
          stringResource(
            when (state.repeatMode) {
              WearProtocol.REPEAT_ALL -> R.string.repeat_all
              WearProtocol.REPEAT_ONE -> R.string.repeat_one
              else -> R.string.repeat_off
            }
          )
        IconToggleButton(
          checked = state.repeatMode != WearProtocol.REPEAT_OFF,
          onCheckedChange = { onToggleRepeat() },
          modifier = Modifier.size(IconButtonDefaults.DefaultButtonSize),
        ) {
          Icon(
            painter =
              painterResource(
                if (state.repeatMode == WearProtocol.REPEAT_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
              ),
            contentDescription = repeatLabel,
          )
        }
      }
      Button(
        onClick = onOpenLibrary,
        modifier = Modifier.padding(top = 10.dp),
        icon = {
          Icon(painter = painterResource(R.drawable.ic_music_note), contentDescription = null)
        },
        label = { Text(stringResource(R.string.offline_music), maxLines = 1) },
      )
    }
  }
}

/**
 * Ambient (low-power) mode: black background, no artwork, no animation – just what's playing. The
 * system only redraws once a minute here, so nothing in this tree may tick.
 */
@Composable
private fun AmbientPlayer(state: PlayerState) {
  Column(
    modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 28.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Text(
      text = state.title,
      style = MaterialTheme.typography.titleMedium,
      color = Color.White,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
    Text(
      text = state.artist.ifBlank { stringResource(R.string.unknown_artist) },
      style = MaterialTheme.typography.bodySmall,
      color = Color.White,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}
