package it.objectfinder.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import it.objectfinder.app.ObjectFinderApplication
import it.objectfinder.app.data.remote.SupabaseException

class ObjectSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ObjectFinderApplication).container
        if (!container.config.isSupabaseConfigured || container.authRepository.session.value == null) {
            // Nessun account: l'app funziona offline, niente da sincronizzare.
            return Result.success()
        }
        val outcome = container.objectRepository.sync()
        return when (val error = outcome.exceptionOrNull()) {
            null -> {
                // Foto nuove degli altri membri: impronte per il riconoscimento.
                runCatching { container.recognitionRepository.indexMissing() }
                Result.success()
            }
            is SupabaseException.NotAuthenticated, is SupabaseException.NotConfigured -> Result.failure()
            // 4xx (RLS, validazione) non si risolvono ritentando: si mostra l'errore in UI.
            is SupabaseException.Http -> if (error.status >= 500 || error.status == 429) Result.retry() else Result.failure()
            else -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
