package echo.music.iad1tya.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import echo.music.iad1tya.wear.R
import echo.music.iad1tya.wear.data.QueueEntry

/** The phone's upcoming queue; tapping an entry jumps to it. */
@Composable
fun QueueScreen(queue: List<QueueEntry>, onSelect: (QueueEntry) -> Unit) {
  val listState = rememberTransformingLazyColumnState()
  ScreenScaffold(scrollState = listState) { contentPadding ->
    TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
      item { ListHeader { Text(stringResource(R.string.up_next)) } }
      if (queue.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      items(queue.size) { position ->
        val entry = queue[position]
        Button(
          onClick = { onSelect(entry) },
          modifier = Modifier.fillMaxWidth(),
          label = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
          secondaryLabel = {
            if (entry.artist.isNotBlank()) {
              Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          },
        )
      }
    }
  }
}
