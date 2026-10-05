package echo.music.iad1tya.wear.data

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable

/** One upcoming track in the phone's queue, as shown on the "Up next" screen. */
@Immutable
data class QueueEntry(
  /** Media item index in the phone's player; sent back to jump to this track. */
  val index: Int,
  val title: String,
  val artist: String,
)

/** Snapshot of the phone's playback state, as last published over the Data Layer. */
@Immutable
data class PlayerState(
  val mediaId: String = "",
  val title: String = "",
  val artist: String = "",
  val album: String = "",
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  /** Position at [receivedAtElapsedMs], not "now" – use [positionAt]. */
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  /** [android.os.SystemClock.elapsedRealtime] when this snapshot arrived on the watch. */
  val receivedAtElapsedMs: Long = 0L,
  val liked: Boolean = false,
  val shuffle: Boolean = false,
  val repeatMode: Int = 0,
  val volume: Int = 0,
  val volumeMax: Int = 0,
  val artwork: Bitmap? = null,
  val queue: List<QueueEntry> = emptyList(),
) {
  /** Playback position extrapolated to [nowElapsedMs] while playing. */
  fun positionAt(nowElapsedMs: Long): Long {
    val extrapolated =
      if (isPlaying && !isBuffering) positionMs + (nowElapsedMs - receivedAtElapsedMs)
      else positionMs
    return if (durationMs > 0) extrapolated.coerceIn(0L, durationMs) else extrapolated.coerceAtLeast(0L)
  }

  fun progressAt(nowElapsedMs: Long): Float =
    if (durationMs > 0) (positionAt(nowElapsedMs).toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** What the watch currently knows about the phone. */
sealed interface PhoneConnection {
  /** Still looking for the phone (first few hundred ms after launch). */
  data object Connecting : PhoneConnection

  /** No reachable phone running Echo Music. */
  data object Disconnected : PhoneConnection

  /** Phone reachable, but it has not published any media. */
  data object Idle : PhoneConnection

  /** Phone reachable and playing / paused on a track. */
  data class Active(val state: PlayerState) : PhoneConnection
}
