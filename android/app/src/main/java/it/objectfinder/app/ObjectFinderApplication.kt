package it.objectfinder.app

import android.app.Application
import it.objectfinder.app.data.AppContainer
import it.objectfinder.app.sync.SyncScheduler
import kotlinx.coroutines.launch

class ObjectFinderApplication : Application() {
    /** Unica istanza di database, client HTTP e repository per tutto il processo. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
        container.appScope.launch { container.photoRepository.purgeStaleCameraFiles() }
        // Impronte per il riconoscimento delle foto già presenti (aggiornamento o foto scaricate).
        container.indexPhotosInBackground()
    }
}
