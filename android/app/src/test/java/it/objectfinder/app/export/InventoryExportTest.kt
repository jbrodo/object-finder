package it.objectfinder.app.export

import it.objectfinder.app.data.local.ObjectEntity
import it.objectfinder.app.data.local.ObjectStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class InventoryExportTest {
    private fun row(name: String, notes: String = "", lon: Double? = -122.084, codes: List<String> = emptyList()) =
        InventoryRow(
            item = ObjectEntity(
                id = "id-$name", projectId = "p", name = name, category = "attrezzi", notes = notes,
                latitude = 37.422, longitude = lon, accuracyMeters = 5.7f, status = ObjectStatus.MAINTENANCE.wire,
                createdAt = 0L, updatedAt = 86_400_000L,
            ),
            codes = codes, photoCount = 2, coverPath = null,
        )

    private val csv = InventoryCsv.build(
        listOf(
            row("Trapano \"Bosch\"", notes = "riga 1\nriga 2; con separatore", codes = listOf("Barcode: 800", "Numero di serie: SN-1")),
            row("=HYPERLINK(\"http://x\")"),
        ),
        zone = ZoneId.of("UTC"),
    )
    private val lines = csv.removePrefix("\uFEFF").split("\n")

    @Test
    fun `utf8 bom and semicolon header for italian excel`() {
        assertTrue(csv.startsWith("\uFEFF"))
        assertEquals(InventoryCsv.header.joinToString(";") { "\"$it\"" }, lines[0])
    }

    @Test
    fun `quotes, newlines and separators inside fields are preserved`() {
        assertTrue(csv.contains("\"Trapano \"\"Bosch\"\"\""))
        assertTrue(csv.contains("\"riga 1\nriga 2; con separatore\""))
        assertTrue(csv.contains("\"Barcode: 800 | Numero di serie: SN-1\""))
    }

    @Test
    fun `formulas in free text are neutralized but negative numbers are not`() {
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://x\"\")\""))
        assertTrue(csv.contains("\"-122.084000\""))
        assertTrue(!csv.contains("\"'-122"))
    }

    @Test
    fun `values are labelled and dates formatted`() {
        assertTrue(csv.contains("\"Manutenzione\""))
        assertTrue(csv.contains("\"1970-01-01 00:00\";\"1970-01-02 00:00\""))
        assertTrue(csv.contains("\"5\";\"\"Barcode") || csv.contains("\"5\";\""))
    }

    @Test
    fun `pdf layout paginates fixed-height cards`() {
        val perPage = InventoryLayout.cardsPerPage
        assertTrue(perPage in 6..10)
        assertEquals(1, InventoryLayout.pageCount(0))
        assertEquals(1, InventoryLayout.pageCount(perPage))
        assertEquals(2, InventoryLayout.pageCount(perPage + 1))
        val pages = InventoryLayout.pages(perPage * 2 + 3)
        assertEquals(3, pages.size)
        assertEquals(perPage * 2 + 3, pages.sumOf { it.count() })
        assertEquals(0 until perPage, pages.first())
    }
}
