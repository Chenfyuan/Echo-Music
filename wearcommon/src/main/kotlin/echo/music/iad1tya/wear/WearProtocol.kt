package echo.music.iad1tya.wear

/**
 * Wire protocol shared by the phone app (`:app`) and the Wear OS app (`:wear`).
 *
 * The phone publishes its playback state as a single Data Layer item at [PATH_STATE]; the watch
 * sends one-shot messages under [PATH_COMMAND_PREFIX] to control playback. Both sides must only use
 * the constants below so the protocol can't drift.
 */
object WearProtocol {
  /** Capability advertised by the phone app so the watch can detect a reachable phone. */
  const val CAPABILITY_PHONE = "echo_music_phone"

  /** Capability advertised by the watch app, so the phone could detect it if ever needed. */
  const val CAPABILITY_WATCH = "echo_music_watch"

  /** Data item holding the current [StateKeys]. */
  const val PATH_STATE = "/echo/state"

  /** Message path prefix: `/echo/cmd/<command>`. */
  const val PATH_COMMAND_PREFIX = "/echo/cmd/"

  fun commandPath(command: String) = PATH_COMMAND_PREFIX + command

  fun commandFrom(path: String): String? =
    path.takeIf { it.startsWith(PATH_COMMAND_PREFIX) }?.removePrefix(PATH_COMMAND_PREFIX)

  /** Command names (the last segment of the message path). */
  object Command {
    const val PLAY_PAUSE = "play_pause"
    const val NEXT = "next"
    const val PREVIOUS = "previous"

    const val TOGGLE_LIKE = "toggle_like"
    const val TOGGLE_SHUFFLE = "toggle_shuffle"
    const val TOGGLE_REPEAT = "toggle_repeat"

    /** Payload: 4-byte big-endian signed number of volume steps (positive = louder). */
    const val VOLUME_STEP = "volume_step"

    /** Payload: 4-byte big-endian queue index to jump to. */
    const val PLAY_QUEUE_ITEM = "play_queue_item"

    /** Ask the phone to (re)publish its state, e.g. when the watch app opens. */
    const val REQUEST_STATE = "request_state"
  }

  /** Keys of the [PATH_STATE] DataMap. */
  object StateKeys {
    const val HAS_MEDIA = "has_media"
    const val MEDIA_ID = "media_id"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val IS_PLAYING = "is_playing"
    const val IS_BUFFERING = "is_buffering"

    /** Playback position in ms, valid at the moment the phone built the state. */
    const val POSITION_MS = "position_ms"
    const val DURATION_MS = "duration_ms"
    const val LIKED = "liked"
    const val SHUFFLE = "shuffle"

    /** One of the `Player.REPEAT_MODE_*` constants: 0 = off, 1 = one, 2 = all. */
    const val REPEAT_MODE = "repeat_mode"

    /** Music stream volume, 0..[VOLUME_MAX]. */
    const val VOLUME = "volume"
    const val VOLUME_MAX = "volume_max"

    /** Album art, a small JPEG [com.google.android.gms.wearable.Asset]. */
    const val ARTWORK = "artwork"

    /** Upcoming queue in playback order, current item first. Parallel arrays. */
    const val QUEUE_TITLES = "queue_titles"
    const val QUEUE_ARTISTS = "queue_artists"

    /** Phone-player media item index of each queue entry; sent back with [Command.PLAY_QUEUE_ITEM]. */
    const val QUEUE_INDICES = "queue_indices"

    /** Forces a change so identical states still re-trigger listeners on the watch. */
    const val UPDATED_AT = "updated_at"
  }

  const val REPEAT_OFF = 0
  const val REPEAT_ONE = 1
  const val REPEAT_ALL = 2

  /** Max number of queue entries sent to the watch. */
  const val MAX_QUEUE_ITEMS = 20

  /** Artwork edge length (px) sent to the watch. */
  const val ARTWORK_SIZE_PX = 320

  /**
   * Offline sync: lets the watch keep songs the phone has downloaded and play them without the
   * phone.
   *
   * 1. Watch → phone message [PATH_OFFLINE_REQUEST], JSON `{"limit": n, "have": [ids]}`.
   * 2. Phone → watch message [PATH_OFFLINE_MANIFEST], JSON `{"ids": [...], "missing": [...]}`:
   *    the full ordered set the watch should hold, and the subset that will be transferred.
   * 3. For each missing id the phone opens a channel [PATH_OFFLINE_SONG_CHANNEL] and writes one
   *    frame, see [OfflineFrame].
   * 4. Phone → watch message [PATH_OFFLINE_DONE] (empty payload) when it has finished or given up.
   */
  const val PATH_OFFLINE_REQUEST = "/echo/offline/request"
  const val PATH_OFFLINE_MANIFEST = "/echo/offline/manifest"
  const val PATH_OFFLINE_SONG_CHANNEL = "/echo/offline/song"
  const val PATH_OFFLINE_DONE = "/echo/offline/done"

  /** Bounds for how many songs the watch may ask for. */
  const val OFFLINE_MIN_LIMIT = 5
  const val OFFLINE_MAX_LIMIT = 100

  /** JSON keys shared by the request, manifest and frame header. */
  object OfflineKeys {
    const val LIMIT = "limit"
    const val HAVE = "have"
    const val IDS = "ids"
    const val MISSING = "missing"

    const val ID = "id"
    const val TITLE = "title"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val DURATION_MS = "duration_ms"
    const val MIME_TYPE = "mime_type"
    const val AUDIO_SIZE = "audio_size"
    const val ARTWORK_SIZE = "artwork_size"
  }

  /**
   * Channel frame layout: `[int32 headerLength][header JSON, UTF-8][artwork JPEG bytes, length =
   * artwork_size][audio bytes, length = audio_size]`. All integers are big-endian.
   */
  object OfflineFrame {
    const val MAX_HEADER_BYTES = 16 * 1024
  }
}
