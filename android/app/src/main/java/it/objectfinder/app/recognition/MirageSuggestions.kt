package it.objectfinder.app.recognition

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.sqrt

/** Suggerimenti ricavati da una foto: nomi possibili e testo letto (etichette, targhette). */
data class MirageSuggestions(val names: List<String>, val texts: List<ReadText>) {
    val isEmpty: Boolean get() = names.isEmpty() && texts.isEmpty()
}

/** Testo letto nella foto; [codeLike] se sembra un codice (lettere e cifre), da collegare come seriale. */
data class ReadText(val value: String, val codeLike: Boolean)

/**
 * Interpretazione del JSON di Mirage (puro, testabile). Il soggetto è di solito l'oggetto grande e
 * sicuro al centro: si ordina per affidabilità × dimensione e si scartano le voci generiche
 * (sfondo, testo, logo…) che non sono un nome utile per l'oggetto.
 */
object MirageParse {
    private val json = Json { ignoreUnknownKeys = true }

    private val generic = setOf(
        "testo", "logo", "icona", "tessuto", "sfondo", "superficie", "pavimento", "muro", "parete",
        "soffitto", "ombra", "riflesso", "luce", "text", "logo", "icon", "background", "floor", "wall",
        "surface", "shadow", "indicatore di indicazione", "schermo", "display",
    )

    const val MIN_OBJECT_SCORE = 0.35
    const val MIN_TEXT_SCORE = 0.5

    fun parse(raw: String): MirageSuggestions {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return MirageSuggestions(emptyList(), emptyList())
        val names = (root["objects"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length >= 3 } ?: return@mapNotNull null
            if (name.lowercase() in generic) return@mapNotNull null
            val score = o["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (score < MIN_OBJECT_SCORE) return@mapNotNull null
            name to score * sqrt(areaFraction(o))
        }
            .sortedByDescending { it.second }
            .map { it.first.replaceFirstChar(Char::uppercase) }
            .distinctBy { it.lowercase() }
            .take(3)
        val texts = (root["text"] as? JsonArray).orEmpty().mapNotNull { element ->
            val t = element as? JsonObject ?: return@mapNotNull null
            val value = t["string"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.length >= 3 } ?: return@mapNotNull null
            val score = t["score"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (score < MIN_TEXT_SCORE) return@mapNotNull null
            ReadText(value, codeLike(value))
        }.distinctBy { it.value.lowercase() }.take(6)
        return MirageSuggestions(names, texts)
    }

    /** Lettere e cifre insieme, oppure solo cifre lunghe: numeri di serie, modelli, matricole. */
    fun codeLike(value: String): Boolean {
        val compact = value.replace(Regex("[\\s\\-_/.:]"), "")
        val digits = compact.count(Char::isDigit)
        val letters = compact.count(Char::isLetter)
        return compact.length >= 5 && digits >= 2 && (letters >= 1 || digits >= 6)
    }

    /** Frazione dell'immagine coperta dal riquadro (coordinate 0–1000). */
    private fun areaFraction(o: JsonObject): Double {
        val box = (o["bbox"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.doubleOrNull } ?: return 0.05
        if (box.size != 4) return 0.05
        val w = (box[2] - box[0]).coerceAtLeast(0.0)
        val h = (box[3] - box[1]).coerceAtLeast(0.0)
        return (w * h / 1_000_000.0).coerceIn(0.0, 1.0)
    }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
