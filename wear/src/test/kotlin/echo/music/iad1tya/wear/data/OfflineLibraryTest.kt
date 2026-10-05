package echo.music.iad1tya.wear.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import echo.music.iad1tya.wear.WearProtocol
import echo.music.iad1tya.wear.WearProtocol.OfflineKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OfflineLibraryTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    File(context.filesDir, "offline").deleteRecursively()
  }

  /** Library bound to this test's (fresh) files dir; bypasses the process-wide singleton. */
  private fun newLibrary(): OfflineLibrary {
    val ctor = OfflineLibrary::class.java.getDeclaredConstructor(Context::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(context)
  }

  private fun frame(id: String, audio: ByteArray, artwork: ByteArray? = null, size: Long = audio.size.toLong()): ByteArray {
    val header =
      JSONObject()
        .put(OfflineKeys.ID, id)
        .put(OfflineKeys.TITLE, "Title $id")
        .put(OfflineKeys.ARTIST, "Artist")
        .put(OfflineKeys.ALBUM, "Album")
        .put(OfflineKeys.DURATION_MS, 1234L)
        .put(OfflineKeys.MIME_TYPE, "audio/webm")
        .put(OfflineKeys.AUDIO_SIZE, size)
        .put(OfflineKeys.ARTWORK_SIZE, artwork?.size ?: 0)
        .toString()
        .toByteArray()
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
      it.writeInt(header.size)
      it.write(header)
      artwork?.let(it::write)
      it.write(audio)
    }
    return bytes.toByteArray()
  }

  @Test
  fun storesSongFromFrame() {
    val library = newLibrary()
    library.applyManifest(listOf("abc"), listOf("abc"))
    val audio = ByteArray(100_000) { (it % 251).toByte() }
    val art = ByteArray(500) { 7 }

    assertTrue(library.receiveSong(ByteArrayInputStream(frame("abc", audio, art))))

    val song = library.songs.value.single()
    assertEquals("Title abc", song.title)
    assertEquals(1234L, song.durationMs)
    assertTrue(File(song.audioPath).readBytes().contentEquals(audio))
    assertTrue(File(song.artworkPath!!).readBytes().contentEquals(art))
  }

  @Test
  fun survivesRestartViaIndex() {
    val first = newLibrary()
    first.applyManifest(listOf("one", "two"), listOf("one", "two"))
    first.receiveSong(ByteArrayInputStream(frame("two", ByteArray(10))))
    first.receiveSong(ByteArrayInputStream(frame("one", ByteArray(10))))

    val second = newLibrary()
    assertEquals(listOf("one", "two"), second.songs.value.map { it.id })
  }

  @Test
  fun truncatedTransferLeavesNoSong() {
    val library = newLibrary()
    library.applyManifest(listOf("abc"), listOf("abc"))
    val truncated = frame("abc", ByteArray(1000)).copyOf(500)

    runCatching { library.receiveSong(ByteArrayInputStream(truncated)) }

    assertTrue(library.songs.value.isEmpty())
    val leftovers = File(context.filesDir, "offline").listFiles().orEmpty().filter { it.name.endsWith(".audio") }
    assertTrue(leftovers.isEmpty())
  }

  @Test
  fun rejectsPathTraversalIds() {
    val library = newLibrary()
    assertFalse(library.receiveSong(ByteArrayInputStream(frame("../evil", ByteArray(10)))))
    assertTrue(library.songs.value.isEmpty())
    assertNull(File(context.filesDir, "evil.audio").takeIf { it.exists() })
  }

  @Test
  fun manifestDropsSongsThePhoneNoLongerOffers() {
    val library = newLibrary()
    library.applyManifest(listOf("a", "b"), listOf("a", "b"))
    library.receiveSong(ByteArrayInputStream(frame("a", ByteArray(10))))
    library.receiveSong(ByteArrayInputStream(frame("b", ByteArray(10))))

    library.applyManifest(listOf("b"), emptyList())

    assertEquals(listOf("b"), library.songs.value.map { it.id })
    assertFalse(File(context.filesDir, "offline/a.audio").exists())
    assertTrue(File(context.filesDir, "offline/b.audio").exists())
    assertEquals(SyncStatus.Finished(0), library.status.value)
  }

  @Test
  fun statusProgressesAndFinishes() {
    val library = newLibrary()
    library.onRequested()
    assertEquals(SyncStatus.Requesting, library.status.value)

    library.applyManifest(listOf("a", "b"), listOf("a", "b"))
    assertEquals(SyncStatus.Syncing(0, 2), library.status.value)

    library.receiveSong(ByteArrayInputStream(frame("a", ByteArray(10))))
    assertEquals(SyncStatus.Syncing(1, 2), library.status.value)
    library.receiveSong(ByteArrayInputStream(frame("b", ByteArray(10))))
    library.onDone()
    assertEquals(SyncStatus.Finished(2), library.status.value)
  }

  @Test
  fun doneBeforeAllSongsArrivedIsFailure() {
    val library = newLibrary()
    library.applyManifest(listOf("a", "b"), listOf("a", "b"))
    library.receiveSong(ByteArrayInputStream(frame("a", ByteArray(10))))
    library.onDone()
    assertEquals(SyncStatus.Failed, library.status.value)
  }

  @Test
  fun protocolLimitsAreSane() {
    assertTrue(WearProtocol.OFFLINE_MIN_LIMIT < WearProtocol.OFFLINE_MAX_LIMIT)
  }
}
