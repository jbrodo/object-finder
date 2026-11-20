package it.objectfinder.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import it.objectfinder.app.data.local.categoryLabel
import it.objectfinder.app.qr.QrCodes
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Impaginazione pura (testabile): schede di altezza fissa su pagine A4. */
object InventoryLayout {
    const val PAGE_WIDTH = 595   // A4 in punti PostScript
    const val PAGE_HEIGHT = 842
    const val MARGIN = 36f
    const val HEADER_HEIGHT = 64f
    const val FOOTER_HEIGHT = 24f
    const val CARD_HEIGHT = 92f

    val cardsPerPage: Int
        get() = ((PAGE_HEIGHT - 2 * MARGIN - HEADER_HEIGHT - FOOTER_HEIGHT) / CARD_HEIGHT).toInt()

    fun pageCount(items: Int): Int = maxOf(1, ceil(items / cardsPerPage.toDouble()).toInt())

    /** Indici degli elementi per ogni pagina. */
    fun pages(items: Int): List<IntRange> =
        (0 until pageCount(items)).map { p -> (p * cardsPerPage) until minOf(items, (p + 1) * cardsPerPage) }
}

/**
 * Inventario stampabile: per ogni oggetto foto di copertina, dati, codici collegati e QR dell'etichetta
 * (scansionando la stampa si apre la scheda dell'oggetto).
 */
object InventoryPdf {
    fun write(projectName: String, rows: List<InventoryRow>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val pages = InventoryLayout.pages(rows.size)
            val generated = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT, Locale.ITALY).format(Date())
            pages.forEachIndexed { index, range ->
                val page = doc.startPage(
                    PdfDocument.PageInfo.Builder(InventoryLayout.PAGE_WIDTH, InventoryLayout.PAGE_HEIGHT, index + 1).create()
                )
                val canvas = page.canvas
                drawHeader(canvas, projectName, generated, rows.size)
                var y = InventoryLayout.MARGIN + InventoryLayout.HEADER_HEIGHT
                if (rows.isEmpty()) {
                    canvas.drawText("Nessun oggetto da esportare.", InventoryLayout.MARGIN, y + 20, Paints.body)
                }
                range.forEach { i ->
                    drawCard(canvas, rows[i], y)
                    y += InventoryLayout.CARD_HEIGHT
                }
                drawFooter(canvas, index + 1, pages.size)
                doc.finishPage(page)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawHeader(canvas: Canvas, project: String, generated: String, count: Int) {
        val x = InventoryLayout.MARGIN
        val top = InventoryLayout.MARGIN
        canvas.drawText("Inventario · $project", x, top + 20, Paints.title)
        canvas.drawText("$count oggetti · generato il $generated", x, top + 38, Paints.muted)
        val lineY = top + InventoryLayout.HEADER_HEIGHT - 12
        canvas.drawLine(x, lineY, InventoryLayout.PAGE_WIDTH - x, lineY, Paints.rule)
    }

    private fun drawFooter(canvas: Canvas, page: Int, total: Int) {
        val y = InventoryLayout.PAGE_HEIGHT - InventoryLayout.MARGIN + 6
        canvas.drawText("Object Finder", InventoryLayout.MARGIN, y, Paints.muted)
        val label = "Pagina $page di $total"
        canvas.drawText(label, InventoryLayout.PAGE_WIDTH - InventoryLayout.MARGIN - Paints.muted.measureText(label), y, Paints.muted)
    }

    private fun drawCard(canvas: Canvas, row: InventoryRow, top: Float) {
        val left = InventoryLayout.MARGIN
        val right = InventoryLayout.PAGE_WIDTH - InventoryLayout.MARGIN
        val thumb = 68f
        val qr = 64f
        val o = row.item

        // Foto di copertina (o riquadro vuoto).
        val photoRect = RectF(left, top + 8, left + thumb, top + 8 + thumb)
        val bitmap = row.coverPath?.let { decodeThumbnail(it, 160) }
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, photoRect, null) // già ritagliata quadrata: nessuna deformazione
            bitmap.recycle()
        } else {
            canvas.drawRect(photoRect, Paints.placeholder)
        }

        // QR dell'etichetta.
        drawQr(canvas, QrCodes.matrix(QrCodes.payloadFor(o.id), margin = 1), right - qr, top + 10, qr)

        // Testo.
        val textLeft = left + thumb + 12
        val textWidth = (right - qr - 12 - textLeft).toInt()
        var y = top + 22
        canvas.drawText(ellipsize(o.name, Paints.name, textWidth), textLeft, y, Paints.name)
        y += 15
        canvas.drawText(
            ellipsize("${categoryLabel(o.category)} · ${o.statusEnum.label} · ${row.photoCount} foto", Paints.body, textWidth),
            textLeft, y, Paints.body,
        )
        y += 13
        val gps = if (o.latitude != null && o.longitude != null)
            "GPS " + it.objectfinder.app.ui.objects.PlaceFormat.coordinates(o.latitude, o.longitude, o.accuracyMeters)
        else "GPS non registrato"
        val where = listOf(row.placePath, o.place).filter { it.isNotBlank() }.joinToString(" · ")
        val position = if (where.isNotBlank()) "Dove: $where · $gps" else gps
        canvas.drawText(ellipsize(position, Paints.body, textWidth), textLeft, y, Paints.body)
        if (row.codes.isNotEmpty()) {
            y += 13
            canvas.drawText(ellipsize("Codici: " + row.codes.joinToString(" | "), Paints.body, textWidth), textLeft, y, Paints.body)
        }
        if (o.notes.isNotBlank()) {
            y += 4
            val notes = StaticLayout.Builder.obtain(o.notes, 0, o.notes.length, Paints.muted, textWidth)
                .setMaxLines(if (row.codes.isEmpty()) 3 else 2)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            canvas.save()
            canvas.translate(textLeft, y)
            notes.draw(canvas)
            canvas.restore()
        }
        val bottom = top + InventoryLayout.CARD_HEIGHT - 4
        canvas.drawLine(left, bottom, right, bottom, Paints.rule)
    }

    private fun drawQr(canvas: Canvas, modules: Array<BooleanArray>, x: Float, y: Float, size: Float) {
        val cell = size / modules.size
        canvas.drawRect(x, y, x + size, y + size, Paints.white)
        modules.forEachIndexed { r, row ->
            row.forEachIndexed { c, on ->
                if (on) canvas.drawRect(x + c * cell, y + r * cell, x + (c + 1) * cell, y + (r + 1) * cell, Paints.black)
            }
        }
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Int) =
        TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END).toString()

    private fun decodeThumbnail(path: String, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val full = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Ritaglio quadrato centrale: niente deformazioni nel riquadro della scheda.
        val side = minOf(full.width, full.height)
        Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
            .also { if (it !== full) full.recycle() }
    }.getOrNull()

    private object Paints {
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f; color = Color.rgb(40, 40, 40) }
        val muted = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(110, 110, 110) }
        val rule = Paint().apply { color = Color.rgb(220, 220, 220); strokeWidth = 0.7f }
        val placeholder = Paint().apply { color = Color.rgb(236, 236, 236) }
        val white = Paint().apply { color = Color.WHITE }
        val black = Paint().apply { color = Color.BLACK }
    }
}
