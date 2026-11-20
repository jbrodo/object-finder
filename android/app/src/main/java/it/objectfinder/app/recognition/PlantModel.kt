package it.objectfinder.app.recognition

import kotlin.math.exp

/** Una specie del modello offline (riga i = uscita i del modello). */
data class PlantLabel(val species: String, val genus: String, val family: String)

/** Da dove viene il riconoscimento. */
enum class PlantSource(val label: String) {
    ONLINE("Pl@ntNet, online"),
    OFFLINE("Sul telefono"),
}

/** Un candidato: specie (o solo genere) con probabilità tra 0 e 1. */
data class PlantGuess(
    /** Nome scientifico senza autore, es. "Salvia rosmarinus". */
    val scientificName: String,
    val genus: String,
    val family: String,
    val probability: Float,
    /** Nomi comuni (in italiano se disponibili; solo online). */
    val commonNames: List<String> = emptyList(),
    /** true se il candidato è il genere (es. tutte le Rosa insieme), non una specie precisa. */
    val genusOnly: Boolean = false,
) {
    /** Nome da proporre per l'oggetto: il nome comune se c'è, altrimenti il nome scientifico. */
    val displayName: String get() = commonNames.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: scientificName

    /** Riga per le note dell'oggetto. */
    val noteLine: String get() = buildString {
        append(if (genusOnly) "Genere: $scientificName" else "Specie: $scientificName")
        if (family.isNotBlank()) append(" (famiglia $family)")
    }
}

data class PlantIdentification(
    val source: PlantSource,
    val guesses: List<PlantGuess>,
    /** Sotto la soglia: il modello non è sicuro (foto non di una pianta, specie non conosciuta…). */
    val confident: Boolean,
)

/** Parte pura e testabile del modello offline (PlantCLEF 2024, MobileNetV4 conv-s, 7.806 specie). */
object PlantModel {
    private val INFRA_RANKS = setOf("subsp.", "var.", "f.")
    const val INPUT_SIZE = 512
    const val CLASSES = 7806
    private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /** Soglie: la specie migliore o il suo genere devono superarle per un risultato "sicuro". */
    const val SPECIES_CONFIDENT = 0.25f
    const val GENUS_CONFIDENT = 0.40f
    /** Con 7.806 classi le probabilità sono basse: conta anche lo stacco netto sul secondo. */
    const val SPECIES_LEAD_MIN = 0.10f
    const val SPECIES_LEAD_RATIO = 3f

    /** Pixel ARGB 512×512 (riga per riga) → tensore NCHW float normalizzato come in metadata.json. */
    fun toInput(argb: IntArray): FloatArray {
        val plane = INPUT_SIZE * INPUT_SIZE
        require(argb.size == plane) { "attesi ${INPUT_SIZE}×$INPUT_SIZE pixel" }
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = argb[i]
            out[i] = ((p shr 16 and 0xFF) / 255f - MEAN[0]) / STD[0]
            out[plane + i] = ((p shr 8 and 0xFF) / 255f - MEAN[1]) / STD[1]
            out[2 * plane + i] = ((p and 0xFF) / 255f - MEAN[2]) / STD[2]
        }
        return out
    }

    fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val e = FloatArray(logits.size) { exp((logits[it] - max).toDouble()).toFloat() }
        val sum = e.sum()
        return FloatArray(e.size) { e[it] / sum }
    }

    /**
     * Prime [top] specie; se la specie migliore è incerta ma il suo genere raccoglie abbastanza
     * probabilità (tipico delle rose da giardino: ibridi che il modello sparge tra le specie
     * selvatiche) il genere va in testa come candidato a sé.
     */
    fun rank(logits: FloatArray, labels: List<PlantLabel>, top: Int = 5): PlantIdentification {
        require(logits.size == labels.size) { "uscite (${logits.size}) ≠ etichette (${labels.size})" }
        val p = softmax(logits)
        val order = p.indices.sortedByDescending { p[it] }.take(top)
        val species = order.map { i ->
            val l = labels[i]
            PlantGuess(withoutAuthor(l.species), l.genus, l.family, p[i])
        }
        val genusTotals = HashMap<String, Float>()
        labels.forEachIndexed { i, l -> genusTotals.merge(l.genus, p[i], Float::plus) }
        val best = species.first()
        val bestGenus = genusTotals.maxBy { it.value }
        val genusFirst = best.probability < SPECIES_CONFIDENT && bestGenus.value >= GENUS_CONFIDENT
        val guesses = if (genusFirst) {
            val family = labels.first { it.genus == bestGenus.key }.family
            listOf(PlantGuess(bestGenus.key, bestGenus.key, family, bestGenus.value, genusOnly = true)) + species.take(top - 1)
        } else species
        val second = species.getOrNull(1)?.probability ?: 0f
        val clearLead = best.probability >= SPECIES_LEAD_MIN && best.probability >= SPECIES_LEAD_RATIO * second
        return PlantIdentification(
            PlantSource.OFFLINE, guesses,
            confident = best.probability >= SPECIES_CONFIDENT || clearLead || genusFirst,
        )
    }

    /** "Salvia rosmarinus Spenn." → "Salvia rosmarinus"; mantiene "×" degli ibridi e le sottospecie. */
    fun withoutAuthor(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val out = mutableListOf(words[0])
        var i = 1
        // Ibrido: "Mentha × piperita".
        if (i < words.size && (words[i] == "×" || words[i] == "x")) { out += "×"; i++ }
        // Epiteto: minuscolo (l'autore inizia con la maiuscola o con una parentesi).
        if (i < words.size && words[i].first().isLowerCase()) { out += words[i]; i++ } else return out.joinToString(" ")
        // Rango infraspecifico, dopo un eventuale autore: "… subsp. italica".
        val rank = words.drop(i).indexOfFirst { it in INFRA_RANKS }
        if (rank >= 0 && i + rank + 1 < words.size) out += listOf(words[i + rank], words[i + rank + 1])
        return out.joinToString(" ")
    }

    /** Righe "specie\tgenere\tfamiglia" (asset plant_labels.tsv, generato dalla build). */
    fun parseLabels(tsv: String): List<PlantLabel> = tsv.lineSequence().filter { it.isNotBlank() }.map { line ->
        val (species, genus, family) = line.split('\t').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        PlantLabel(species, genus, family)
    }.toList()
}
