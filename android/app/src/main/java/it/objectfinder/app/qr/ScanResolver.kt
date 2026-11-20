package it.objectfinder.app.qr

import androidx.activity.ComponentActivity
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import it.objectfinder.app.data.ActiveProject
import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.data.local.ObjectEntity

sealed interface ScanOutcome {
    data class Open(val objectId: String) : ScanOutcome
    /** L'oggetto è di un altro progetto dell'utente: si può passare a quello e aprirlo. */
    data class SwitchProject(val project: ActiveProject, val objectId: String, val objectName: String) : ScanOutcome
    /** Più oggetti del progetto attivo condividono il codice (es. stesso modello di prodotto). */
    data class Choose(val code: String, val objects: List<ObjectEntity>) : ScanOutcome
    data class Message(val text: String) : ScanOutcome
    /** Codice (o tag NFC) non collegato a nessun oggetto: si può collegarlo subito. */
    data class Unlinked(val code: String, val type: IdentifierType) : ScanOutcome
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
    /** [type]: come salvarlo se non è collegato (QR di terzi, barcode, UID di un tag NFC). */
    suspend fun resolve(raw: String?, type: IdentifierType = IdentifierType.BARCODE): ScanOutcome {
        val id = QrCodes.parseObjectId(raw) ?: return resolveLinkedCode(raw.orEmpty().trim(), type)
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
    private suspend fun resolveLinkedCode(code: String, type: IdentifierType): ScanOutcome {
        if (code.isEmpty()) return ScanOutcome.Message("Codice vuoto")
        val matches = findObjectsByCode(code)
        if (matches.isEmpty()) return ScanOutcome.Unlinked(code, type)
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
    const val UNAVAILABLE_MESSAGE =
        "Scanner non disponibile: aggiorna Google Play services, oppure inquadra l'etichetta " +
            "con la fotocamera del telefono."
    const val DOWNLOADING_MESSAGE =
        "Sto scaricando lo scanner da Google Play services: riprova tra qualche secondo."

    /**
     * Verifica prima, con l'API ufficiale, che il modulo scanner sia installato (con Play services
     * datati la verifica fallisce invece di lasciare la scansione in sospeso); se manca ne avvia il
     * download. Chiudere lo scanner non è un errore: nessun messaggio.
     */
    fun scan(activity: ComponentActivity, onResult: (ScannedCode) -> Unit, onError: (String) -> Unit) {
        // Tutti i formati (QR + barcode prodotto EAN/UPC/Code128…): passare FORMAT_ALL_FORMATS (= 0)
        // insieme a FORMAT_QR_CODE restringeva la scansione al solo QR.
        // Niente auto-zoom: su alcuni dispositivi continua a ingrandire senza mai decodificare.
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        val modules = ModuleInstall.getClient(activity)

        modules.areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    startScan(scanner, onResult, onError)
                } else {
                    modules.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                    onError(DOWNLOADING_MESSAGE)
                }
            }
            .addOnFailureListener { onError(UNAVAILABLE_MESSAGE) }
    }

    private fun startScan(
        scanner: GmsBarcodeScanner,
        onResult: (ScannedCode) -> Unit,
        onError: (String) -> Unit,
    ) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw.isNullOrBlank()) onError("Codice vuoto")
                else onResult(ScannedCode(raw, isQr = barcode.format == Barcode.FORMAT_QR_CODE))
            }
            .addOnFailureListener { e -> failureMessage(e)?.let(onError) }
        // Annullamento (finestra chiusa dall'utente): nessun messaggio.
    }

    /** Messaggio per un errore dello scanner; null se è un'uscita volontaria (nessun messaggio). */
    internal fun failureMessage(e: Exception): String? = when ((e as? MlKitException)?.errorCode) {
        MlKitException.CODE_SCANNER_CANCELLED, MlKitException.CANCELLED,
        MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> null
        MlKitException.CODE_SCANNER_UNAVAILABLE,
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD -> UNAVAILABLE_MESSAGE
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
            "Permesso fotocamera negato a Google Play services: abilitalo dalle impostazioni di sistema."
        else -> "Scansione non riuscita: riprova inquadrando il codice da più vicino e con più luce."
    }
}
