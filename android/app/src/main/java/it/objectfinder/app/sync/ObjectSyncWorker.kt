package it.objectfinder.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.objectfinder.app.data.AppContainer

class ObjectSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val result = AppContainer(applicationContext).repository.syncPending()
        return if (result.isSuccess) Result.success() else Result.retry()
    }
}
