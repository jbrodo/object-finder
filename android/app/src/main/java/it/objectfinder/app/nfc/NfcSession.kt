package it.objectfinder.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import it.objectfinder.app.qr.QrCodes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Un tag avvicinato con l'app aperta: link Object Finder (se c'è) e identificativo del chip. */
data class NfcRead(val link: String?, val uid: String)

/**
 * Lettura NFC con l'app in primo piano ("reader mode"): ogni tag avvicinato arriva all'app invece che
 * all'app Tag di sistema. Una scrittura di etichetta o un collegamento in corso ([exclusive]) hanno la
 * precedenza; altrimenti il tag viene pubblicato in [reads] e l'app decide (apre l'oggetto o propone
 * di collegarlo).
 */
object NfcSession {
    @Volatile
    var exclusive: ((Tag) -> Unit)? = null

    private val _reads = MutableSharedFlow<NfcRead>(extraBufferCapacity = 4)
    val reads: SharedFlow<NfcRead> = _reads.asSharedFlow()

    private const val FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V

    fun start(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching {
            adapter.enableReaderMode(activity, { tag ->
                val handler = exclusive
                if (handler != null) handler(tag) else _reads.tryEmit(read(tag))
            }, FLAGS, null)
        }
    }

    fun stop(activity: Activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: return
        runCatching { adapter.disableReaderMode(activity) }
    }

    /** Contenuto utile del tag: il primo link Object Finder nei record NDEF e l'UID. */
    fun read(tag: Tag): NfcRead {
        val link = runCatching {
            Ndef.get(tag)?.cachedNdefMessage?.records?.firstNotNullOfOrNull { record ->
                record.toUri()?.toString()?.takeIf { QrCodes.parseObjectId(it) != null }
            }
        }.getOrNull()
        return NfcRead(link, uidHex(tag.id))
    }

    /** UID del chip in esadecimale maiuscolo (es. "04A1B2C3D4E5F6"): il codice da collegare. */
    fun uidHex(id: ByteArray?): String = id?.joinToString("") { "%02X".format(it.toInt() and 0xFF) }.orEmpty()
}
