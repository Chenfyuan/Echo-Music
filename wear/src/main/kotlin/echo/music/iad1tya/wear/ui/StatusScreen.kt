package echo.music.iad1tya.wear.ui

import android.content.Intent
import androidx.core.net.toUri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.remote.interactions.RemoteActivityHelper
import echo.music.iad1tya.wear.R
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

enum class StatusKind {
  Connecting,
  Disconnected,
  NothingPlaying,
}

/** Full-screen state for "no phone", "phone but nothing playing" and "still connecting". */
@Composable
fun StatusScreen(kind: StatusKind) {
  val scrollState = rememberScrollState()
  ScreenScaffold(scrollState = scrollState) { contentPadding ->
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(contentPadding),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
      when (kind) {
        StatusKind.Connecting -> {
          CircularProgressIndicator(modifier = Modifier.size(32.dp))
          Text(
            text = stringResource(R.string.connecting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StatusKind.Disconnected -> {
          Icon(
            painter = painterResource(R.drawable.ic_smartphone),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = stringResource(R.string.phone_not_connected),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
          )
          Text(
            text = stringResource(R.string.phone_not_connected_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
        StatusKind.NothingPlaying -> {
          Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = stringResource(R.string.nothing_playing),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
          )
          Text(
            text = stringResource(R.string.nothing_playing_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
          OpenOnPhoneButton()
        }
      }
    }
  }
}

/** Launches Echo Music on the paired phone (shows the standard Wear "Open on phone" animation). */
@Composable
private fun OpenOnPhoneButton() {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  Button(
    onClick = {
      scope.launch {
        val intent =
          Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData("echomusic://open".toUri())
        val message =
          try {
            RemoteActivityHelper(context).startRemoteActivity(intent).await()
            R.string.opened_on_phone
          } catch (e: Exception) {
            R.string.open_on_phone_failed
          }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
      }
    },
    icon = {
      Icon(painter = painterResource(R.drawable.ic_smartphone), contentDescription = null)
    },
    label = { Text(stringResource(R.string.open_on_phone)) },
  )
}
