package it.objectfinder.app.nfc

import android.content.Context
import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import it.objectfinder.app.qr.QrCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Etichette NFC: il tag contiene lo stesso link del QR (`objectfinder://object/<id>`) più un
 * Android Application Record, così avvicinando il telefono si apre direttamente la scheda
 * dell'oggetto, anche ad app chiusa. Nessun dato oltre l'id finisce sul tag.
 */
object NfcTags {
    enum class Availability { MISSING, DISABLED, READY }

    fun availability(context: Context): Availability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return Availability.MISSING
        return if (adapter.isEnabled) Availability.READY else Availability.DISABLED
    }

    fun message(objectId: String, packageName: String): NdefMessage = NdefMessage(
        NdefRecord.createUri(QrCodes.payloadFor(objectId)),
        NdefRecord.createApplicationRecord(packageName),
    )

    sealed interface WriteResult {
        data object Written : WriteResult
        data object ReadOnly : WriteResult
        data class TooSmall(val needed: Int, val available: Int) : WriteResult
        data object Unsupported : WriteResult
        data object Lost : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    /** Scrive il messaggio sul tag (formattandolo se vergine). Da chiamare fuori dal main thread. */
    suspend fun write(tag: Tag, message: NdefMessage): WriteResult = withContext(Dispatchers.IO) {
        val size = message.byteArrayLength
        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    checkCapacity(ndef.isWritable, ndef.maxSize, size)?.let { return@withContext it }
                    ndef.writeNdefMessage(message)
                } finally {
                    runCatching { ndef.close() }
                }
                return@withContext WriteResult.Written
            }
            val formatable = NdefFormatable.get(tag) ?: return@withContext WriteResult.Unsupported
            formatable.connect()
            try {
                formatable.format(message)
            } finally {
                runCatching { formatable.close() }
            }
            WriteResult.Written
        } catch (_: TagLostException) {
            WriteResult.Lost
        } catch (e: IOException) {
            WriteResult.Failed(e.message ?: "errore di comunicazione")
        } catch (e: FormatException) {
            WriteResult.Failed(e.message ?: "formato non valido")
        }
    }

    /** Politica di scrittura (pura): tag bloccato o troppo piccolo non vengono toccati. */
    fun checkCapacity(writable: Boolean, maxSize: Int, needed: Int): WriteResult? = when {
        !writable -> WriteResult.ReadOnly
        needed > maxSize -> WriteResult.TooSmall(needed, maxSize)
        else -> null
    }

    /** Messaggio per l'utente, con il rimedio. */
    fun describe(result: WriteResult): String = when (result) {
        WriteResult.Written -> "Tag scritto: avvicinando il telefono si apre la scheda dell'oggetto."
        WriteResult.ReadOnly -> "Questo tag è bloccato in sola lettura: usane uno nuovo."
        is WriteResult.TooSmall ->
            "Tag troppo piccolo (${result.available} byte, ne servono ${result.needed}): usa un NTAG213 o più capiente."
        WriteResult.Unsupported -> "Tipo di tag non supportato: usa un tag NFC NTAG (213, 215 o 216)."
        WriteResult.Lost -> "Il tag si è allontanato durante la scrittura: tienilo fermo sul retro del telefono e riprova."
        is WriteResult.Failed -> "Scrittura non riuscita (${result.reason}): riprova."
    }
}
