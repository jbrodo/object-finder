package it.objectfinder.app.recognition

import it.objectfinder.app.data.remote.SupabaseException
import java.io.File

/** Esito per l'interfaccia. [note] spiega perché si è usato il telefono invece di Pl@ntNet. */
sealed interface PlantOutcome {
    data class Found(val identification: PlantIdentification, val note: String? = null) : PlantOutcome
    /** Pl@ntNet ha risposto che nella foto non riconosce una pianta. */
    data object NotAPlant : PlantOutcome
    /** Online non disponibile e modello offline non ancora scaricato. */
    data class NeedsModel(val note: String?) : PlantOutcome
    data class Failed(val message: String) : PlantOutcome
}

/** Riconoscimento offline (modello sul telefono), astratto per i test. */
interface OfflinePlants {
    val isSupported: Boolean get() = true
    val isModelReady: Boolean
    suspend fun identify(photo: File): PlantIdentification?
}

/**
 * Prima Pl@ntNet (flora mondiale, piante di casa e d'orto) se c'è un account e la funzione è
 * configurata; altrimenti, o se la rete/quota manca, il modello sul telefono (flora europea).
 */
class PlantIdentifier(
    private val offline: OfflinePlants,
    /** null = utente non collegato (archivio locale): niente online. */
    private val online: (suspend (File) -> List<PlantGuess>)?,
) {
    /** Diventa false alla prima risposta "not_configured": non si riprova a ogni foto. */
    @Volatile var onlineAvailable: Boolean = true
        private set

    suspend fun identify(photo: File): PlantOutcome {
        var note: String? = null
        val remote = online
        if (remote != null && onlineAvailable) {
            val result = runCatching { remote(photo) }
            val error = result.exceptionOrNull()
            if (error == null) {
                val guesses = result.getOrThrow()
                if (guesses.isEmpty()) return PlantOutcome.NotAPlant
                return PlantOutcome.Found(
                    PlantIdentification(PlantSource.ONLINE, guesses, confident = guesses.first().probability >= ONLINE_CONFIDENT),
                )
            }
            note = when {
                // 503 not_configured (manca la chiave) o 404 (funzione non pubblicata): solo telefono.
                error is SupabaseException.Http && (error.status == 503 || error.status == 404) -> { onlineAvailable = false; null }
                error is SupabaseException.Http && error.status == 429 -> "Limite giornaliero online raggiunto: risultato dal telefono."
                error is SupabaseException.NotAuthenticated -> null
                else -> "Pl@ntNet non raggiungibile: risultato dal telefono."
            }
        }
        if (!offline.isSupported) {
            return PlantOutcome.Failed(note ?: "Su questo telefono le piante si riconoscono solo online (Pl@ntNet).")
        }
        if (!offline.isModelReady) return PlantOutcome.NeedsModel(note)
        val local = runCatching { offline.identify(photo) }.getOrNull()
            ?: return PlantOutcome.Failed("Non riesco a leggere la foto")
        return PlantOutcome.Found(local, note)
    }

    companion object {
        /** Pl@ntNet: punteggio della prima specie sotto il quale il risultato è incerto. */
        const val ONLINE_CONFIDENT = 0.20f
    }
}
