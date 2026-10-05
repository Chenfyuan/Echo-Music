package echo.music.iad1tya.wearsync

import android.content.Context
import android.graphics.Bitmap
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import echo.music.iad1tya.wear.WearProtocol
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val JPEG_QUALITY = 80

/** Loads [url] as a small JPEG sized for the watch, or `null` if it can't be loaded. */
suspend fun loadWearArtworkJpeg(context: Context, url: String?): ByteArray? {
  if (url.isNullOrBlank()) return null
  return withContext(Dispatchers.IO) {
    val request =
      ImageRequest.Builder(context.applicationContext)
        .data(url)
        .size(WearProtocol.ARTWORK_SIZE_PX)
        .allowHardware(false)
        .build()
    val result = context.applicationContext.imageLoader.execute(request) as? SuccessResult
    result?.image?.toBitmap()?.let { bitmap ->
      ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        .toByteArray()
    }
  }
}
