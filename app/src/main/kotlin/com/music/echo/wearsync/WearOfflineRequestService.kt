package echo.music.iad1tya.wearsync

import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import echo.music.iad1tya.wear.WearProtocol
import timber.log.Timber

/**
 * Wakes the phone app when the watch asks for an offline sync. The transfer itself can take
 * minutes, so it is handed to an expedited [WearOfflineSyncWorker] (foreground service) rather
 * than run in this short-lived listener service.
 */
class WearOfflineRequestService : WearableListenerService() {
  override fun onMessageReceived(event: MessageEvent) {
    if (event.path != WearProtocol.PATH_OFFLINE_REQUEST) return
    val request =
      WorkManager.getInstance(applicationContext)
        .enqueueUniqueWork(
          WearOfflineSyncWorker.UNIQUE_NAME,
          // A sync that is already running will deliver the same songs; ignore duplicate taps.
          ExistingWorkPolicy.KEEP,
          OneTimeWorkRequestBuilder<WearOfflineSyncWorker>()
            .setInputData(
              Data.Builder()
                .putString(WearOfflineSyncWorker.KEY_NODE_ID, event.sourceNodeId)
                .putString(WearOfflineSyncWorker.KEY_REQUEST, String(event.data, Charsets.UTF_8))
                .build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        )
    Timber.tag("WearOffline").d("Enqueued offline sync for node %s: %s", event.sourceNodeId, request)
  }
}
