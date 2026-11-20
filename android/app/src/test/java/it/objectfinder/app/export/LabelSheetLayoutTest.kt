package it.objectfinder.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSheetLayoutTest {
    @Test
    fun `21 labels per A4 page`() {
        assertEquals(1, LabelSheetLayout.pageCount(0))
        assertEquals(1, LabelSheetLayout.pageCount(21))
        assertEquals(2, LabelSheetLayout.pageCount(22))
        assertEquals(3, LabelSheetLayout.pageCount(50))
    }

    @Test
    fun `cells fill rows left to right and restart on each page`() {
        val (x0, y0) = LabelSheetLayout.cellOrigin(0)
        val (x1, y1) = LabelSheetLayout.cellOrigin(1)
        val (x3, y3) = LabelSheetLayout.cellOrigin(3)
        assertEquals(y0, y1, 0f)
        assertTrue(x1 > x0)
        assertEquals(x0, x3, 0f)
        assertTrue(y3 > y0)
        assertEquals(LabelSheetLayout.cellOrigin(0), LabelSheetLayout.cellOrigin(21)) // seconda pagina
    }

    @Test
    fun `grid fits the page with its margins`() {
        val (x, y) = LabelSheetLayout.cellOrigin(20) // ultima cella
        assertEquals(LabelSheetLayout.PAGE_WIDTH - LabelSheetLayout.MARGIN_X, x + LabelSheetLayout.cellWidth, 0.01f)
        assertEquals(LabelSheetLayout.PAGE_HEIGHT - LabelSheetLayout.MARGIN_Y, y + LabelSheetLayout.cellHeight, 0.01f)
    }
}
