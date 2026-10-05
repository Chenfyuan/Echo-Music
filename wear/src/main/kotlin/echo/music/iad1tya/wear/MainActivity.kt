package echo.music.iad1tya.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import echo.music.iad1tya.wear.ui.EchoWearApp
import echo.music.iad1tya.wear.ui.theme.EchoWearTheme

class MainActivity : ComponentActivity() {
  private var isAmbient by mutableStateOf(false)

  private val ambientCallback =
    object : AmbientLifecycleObserver.AmbientLifecycleCallback {
      override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
        isAmbient = true
      }

      override fun onExitAmbient() {
        isAmbient = false
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

    setContent { EchoWearTheme { EchoWearApp(isAmbient = isAmbient) } }
  }
}
