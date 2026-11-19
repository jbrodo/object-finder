package it.objectfinder.app.qr

import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import it.objectfinder.app.data.ActiveProject
import it.objectfinder.app.data.local.ObjectEntity

sealed interface ScanOutcome {
    data class Open(val objectId: String) : ScanOutcome
    /** L'oggetto è di un altro progetto dell'utente: si può passare a quello e aprirlo. */
    data class SwitchProject(val project: ActiveProject, val objectId: String, val objectName: String) : ScanOutcome
    data class Message(val text: String) : ScanOutcome
}

/** Decide cosa fare con un contenuto scansionato (QR o deep link). Nessuna dipendenza Android. */
class ScanResolver(
    private val findObject: suspend (String) -> ObjectEntity?,
    private val currentProjectId: () -> String,
    private val knownProjects: suspend () -> List<ActiveProject>,
) {
    suspend fun resolve(raw: String?): ScanOutcome {
        val id = QrCodes.parseObjectId(raw)
            ?: return ScanOutcome.Message("Codice non riconosciuto: ${raw.orEmpty().take(40)}")
        val item = findObject(id)
            ?: return ScanOutcome.Message(
                "Oggetto non presente su questo dispositivo. Se è di un tuo progetto, sincronizza e riprova."
            )
        if (item.projectId == currentProjectId()) return ScanOutcome.Open(id)
        val project = knownProjects().firstOrNull { it.id == item.projectId }
            ?: return ScanOutcome.Message("L'oggetto «${item.name}» appartiene a un progetto non attivo.")
        return ScanOutcome.SwitchProject(project, id, item.name)
    }
}

/**
 * Scanner di Google Play services: interfaccia fornita dal sistema, nessun permesso fotocamera.
 * Al primo uso il modulo può essere scaricato on-demand.
 */
object CodeScanner {
    private const val UI_TIMEOUT_MS = 10_000L
    const val UNAVAILABLE_MESSAGE =
        "Scanner non disponibile: aggiorna Google Play services, oppure inquadra l'etichetta " +
            "con la fotocamera del telefono."

    fun scan(activity: ComponentActivity, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ALL_FORMATS)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        // Richiesta di installazione anticipata del modulo (no-op se già presente).
        ModuleInstall.getClient(activity)
            .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())

        // L'interfaccia dello scanner è un'altra activity: se la nostra va in pausa, è comparsa.
        var uiShown = false
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) uiShown = true }
        activity.lifecycle.addObserver(observer)

        var settled = false
        fun settle(block: () -> Unit) {
            if (settled) return
            settled = true
            activity.lifecycle.removeObserver(observer)
            block()
        }
        scanner.startScan()
            .addOnSuccessListener { barcode -> settle { barcode.rawValue?.let(onResult) ?: onError("Codice vuoto") } }
            // Annullato dall'utente solo se lo scanner era visibile; altrimenti non è disponibile.
            .addOnCanceledListener { settle { if (!uiShown) onError(UNAVAILABLE_MESSAGE) } }
            .addOnFailureListener { settle { onError(UNAVAILABLE_MESSAGE) } }

        // Con Play services datati il task può non completarsi mai: se dopo il timeout
        // l'interfaccia non è mai comparsa, si avvisa l'utente.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!uiShown) settle { onError(UNAVAILABLE_MESSAGE) }
        }, UI_TIMEOUT_MS)
    }
}
