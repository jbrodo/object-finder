package it.objectfinder.app.export

import it.objectfinder.app.data.local.IdentifierType
import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.categoryLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Un oggetto con i dati collegati, pronto per l'export. */
data class InventoryRow(
    val item: ObjectEntity,
    /** Etichetta leggibile dei codici collegati, es. "Numero di serie: SN-1". */
    val codes: List<String>,
    val photoCount: Int,
    /** Foto di copertina locale (per il PDF), se presente. */
    val coverPath: String?,
)

/**
 * CSV per fogli di calcolo: separatore `;` (Excel in italiano), UTF-8 con BOM (accenti corretti),
 * campi tra virgolette secondo RFC 4180. I valori che inizierebbero con = + - @ vengono neutralizzati
 * con un apostrofo per evitare l'esecuzione di formule all'apertura (CSV injection).
 */
object InventoryCsv {
    const val SEPARATOR = ';'
    private const val BOM = "\uFEFF"

    val header = listOf(
        "Nome", "Categoria", "Stato", "Note", "Latitudine", "Longitudine", "Precisione (m)",
        "Codici collegati", "Foto", "Creato", "Aggiornato", "Id",
    )

    fun build(rows: List<InventoryRow>, zone: ZoneId = ZoneId.systemDefault()): String {
        val dates = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder(BOM)
        sb.appendLine(header.joinToString(SEPARATOR.toString()) { field(it) })
        rows.forEach { row ->
            val o = row.item
            // Testo libero (neutralizzato contro le formule) e valori generati dall'app (numeri, date, id).
            val values = listOf(
                field(o.name),
                field(categoryLabel(o.category)),
                field(o.statusEnum.label),
                field(o.notes),
                number(o.latitude?.let { String.format(Locale.ROOT, "%.6f", it) }),
                number(o.longitude?.let { String.format(Locale.ROOT, "%.6f", it) }),
                number(o.accuracyMeters?.toInt()?.toString()),
                field(row.codes.joinToString(" | ")),
                number(row.photoCount.toString()),
                number(dates.format(Instant.ofEpochMilli(o.createdAt))),
                number(dates.format(Instant.ofEpochMilli(o.updatedAt))),
                number(o.id),
            )
            sb.appendLine(values.joinToString(SEPARATOR.toString()))
        }
        return sb.toString()
    }

    /** Campo di testo libero: tra virgolette, virgolette interne raddoppiate, formule neutralizzate. */
    internal fun field(raw: String): String {
        val safe = if (raw.isNotEmpty() && raw[0] in "=+-@\t\r") "'$raw" else raw
        return quote(safe)
    }

    /** Valore generato dall'app (numero, data, id): non va alterato (es. longitudini negative). */
    internal fun number(raw: String?): String = quote(raw.orEmpty())

    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    fun codeLabel(type: IdentifierType, value: String) = "${type.label}: $value"
}
