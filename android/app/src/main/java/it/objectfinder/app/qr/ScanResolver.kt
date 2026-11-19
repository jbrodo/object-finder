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
    /** Più oggetti del progetto attivo condividono il codice (es. stesso modello di prodotto). */
    data class Choose(val code: String, val objects: List<ObjectEntity>) : ScanOutcome
    data class Message(val text: String) : ScanOutcome
}

/** Contenuto letto dallo scanner. */
data class ScannedCode(val raw: String, val isQr: Boolean)

/** Decide cosa fare con un contenuto scansionato (QR o deep link). Nessuna dipendenza Android. */
class ScanResolver(
    private val findObject: suspend (String) -> ObjectEntity?,
    private val findObjectsByCode: suspend (String) -> List<ObjectEntity> = { emptyList() },
    private val currentProjectId: () -> String,
    private val knownProjects: suspend () -> List<ActiveProject>,
) {
    suspend fun resolve(raw: String?): ScanOutcome {
        val id = QrCodes.parseObjectId(raw) ?: return resolveLinkedCode(raw.orEmpty().trim())
        val item = findObject(id)
            ?: return ScanOutcome.Message(
                "Oggetto non presente su questo dispositivo. Se è di un tuo progetto, sincronizza e riprova."
            )
        if (item.projectId == currentProjectId()) return ScanOutcome.Open(id)
        val project = knownProjects().firstOrNull { it.id == item.projectId }
            ?: return ScanOutcome.Message("L'oggetto «${item.name}» appartiene a un progetto non attivo.")
        return ScanOutcome.SwitchProject(project, id, item.name)
    }

    /** Barcode/seriale/QR di terzi: cerca gli oggetti che hanno quel codice collegato. */
    private suspend fun resolveLinkedCode(code: String): ScanOutcome {
        if (code.isEmpty()) return ScanOutcome.Message("Codice vuoto")
        val matches = findObjectsByCode(code)
        if (matches.isEmpty()) {
            return ScanOutcome.Message(
                "Il codice ${code.take(40)} non è collegato a nessun oggetto. " +
                    "Aprine uno e usa «Collega codice»."
            )
        }
        val current = currentProjectId()
        val here = matches.filter { it.projectId == current }
        return when {
            here.size == 1 -> ScanOutcome.Open(here.single().id)
            here.size > 1 -> ScanOutcome.Choose(code, here)
            else -> {
                val item = matches.first()
                val project = knownProjects().firstOrNull { it.id == item.projectId }
                if (project != null && matches.all { it.projectId == item.projectId } && matches.size == 1) {
                    ScanOutcome.SwitchProject(project, item.id, item.name)
                } else {
                    ScanOutcome.Message("Il codice è collegato a oggetti di un progetto non attivo.")
                }
            }
        }
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

    fun scan(activity: ComponentActivity, onResult: (ScannedCode) -> Unit, onError: (String) -> Unit) {
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
            .addOnSuccessListener { barcode ->
                settle {
                    val raw = barcode.rawValue
                    if (raw.isNullOrBlank()) onError("Codice vuoto")
                    else onResult(ScannedCode(raw, isQr = barcode.format == Barcode.FORMAT_QR_CODE))
                }
            }
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
