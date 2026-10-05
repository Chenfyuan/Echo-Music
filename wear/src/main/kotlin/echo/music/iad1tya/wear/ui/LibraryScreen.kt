package echo.music.iad1tya.wear.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import echo.music.iad1tya.wear.R
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.data.OfflineSong
import echo.music.iad1tya.wear.data.SyncStatus

private val LIMIT_STEPS = listOf(10, 25, 50, WearProtocol.OFFLINE_MAX_LIMIT)

/**
 * Songs stored on the watch. Playing one starts the watch's own player, so it works with no phone;
 * syncing copies the phone's most recent downloads over.
 */
@Composable
fun LibraryScreen(
  songs: List<OfflineSong>,
  status: SyncStatus,
  limit: Int,
  totalSizeBytes: Long,
  phoneReachable: Boolean,
  onSync: () -> Unit,
  onSetLimit: (Int) -> Unit,
  onPlay: (index: Int) -> Unit,
) {
  val context = LocalContext.current
  val listState = rememberTransformingLazyColumnState()
  val syncing = status is SyncStatus.Requesting || status is SyncStatus.Syncing

  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      item { ListHeader { Text(stringResource(R.string.offline_music)) } }

      item {
        Button(
          onClick = onSync,
          enabled = phoneReachable && !syncing,
          modifier = Modifier.fillMaxWidth(),
          icon = { Icon(painter = painterResource(R.drawable.ic_sync), contentDescription = null) },
          label = { Text(stringResource(R.string.sync_from_phone), maxLines = 1) },
          secondaryLabel = {
            Text(
              text =
                when {
                  status is SyncStatus.Requesting -> stringResource(R.string.sync_requesting)
                  status is SyncStatus.Syncing ->
                    stringResource(R.string.sync_progress, status.done + 1, status.total)
                  status is SyncStatus.Failed -> stringResource(R.string.sync_failed)
                  !phoneReachable -> stringResource(R.string.sync_needs_phone)
                  status is SyncStatus.Finished -> stringResource(R.string.sync_up_to_date)
                  songs.isEmpty() -> stringResource(R.string.offline_none)
                  else ->
                    context.resources.getQuantityString(
                      R.plurals.offline_summary,
                      songs.size,
                      songs.size,
                      Formatter.formatShortFileSize(context, totalSizeBytes),
                    )
                },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          },
        )
      }

      item {
        FilledTonalButton(
          onClick = {
            onSetLimit(LIMIT_STEPS.firstOrNull { it > limit } ?: LIMIT_STEPS.first())
          },
          enabled = !syncing,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.songs_to_sync), maxLines = 1) },
          secondaryLabel = { Text(limit.toString()) },
        )
      }

      if (songs.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.offline_empty_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }

      items(songs.size) { index ->
        val song = songs[index]
        Button(
          onClick = { onPlay(index) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          secondaryLabel = {
            if (song.artist.isNotBlank()) {
              Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          },
        )
      }
    }
  }
}
