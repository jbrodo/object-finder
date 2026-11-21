package it.objectfinder.app.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import it.objectfinder.app.qr.QrCodes
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Un'etichetta del foglio: QR dell'oggetto, nome e (se c'è) il "Dove". */
data class SheetLabel(val objectId: String, val name: String, val place: String)

/**
 * Impaginazione pura (testabile) del foglio A4 di etichette: griglia 3 × 7, come i fogli adesivi
 * più comuni (70 × 42 mm circa). Coordinate in punti PostScript.
 */
object LabelSheetLayout {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val COLUMNS = 3
    const val ROWS = 7
    const val MARGIN_X = 14f
    const val MARGIN_Y = 24f
    const val PER_PAGE = COLUMNS * ROWS

    val cellWidth: Float get() = (PAGE_WIDTH - 2 * MARGIN_X) / COLUMNS
    val cellHeight: Float get() = (PAGE_HEIGHT - 2 * MARGIN_Y) / ROWS

    fun pageCount(labels: Int): Int = maxOf(1, (labels + PER_PAGE - 1) / PER_PAGE)

    /** Angolo in alto a sinistra della cella [index] (globale) nella sua pagina. */
    fun cellOrigin(index: Int): Pair<Float, Float> {
        val inPage = index % PER_PAGE
        val column = inPage % COLUMNS
        val row = inPage / COLUMNS
        return (MARGIN_X + column * cellWidth) to (MARGIN_Y + row * cellHeight)
    }
}

object LabelSheetPdf {
    fun write(labels: List<SheetLabel>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            for (page in 0 until LabelSheetLayout.pageCount(labels.size)) {
                val pdfPage = doc.startPage(
                    PdfDocument.PageInfo.Builder(LabelSheetLayout.PAGE_WIDTH, LabelSheetLayout.PAGE_HEIGHT, page + 1).create()
                )
                val from = page * LabelSheetLayout.PER_PAGE
                labels.subList(from, minOf(labels.size, from + LabelSheetLayout.PER_PAGE)).forEachIndexed { i, label ->
                    drawLabel(pdfPage.canvas, label, LabelSheetLayout.cellOrigin(from + i))
                }
                doc.finishPage(pdfPage)
            }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun drawLabel(canvas: Canvas, label: SheetLabel, origin: Pair<Float, Float>) {
        val (x, y) = origin
        val w = LabelSheetLayout.cellWidth
        val h = LabelSheetLayout.cellHeight
        // Bordo leggero: guida per il taglio su carta normale.
        canvas.drawRect(x + 2, y + 2, x + w - 2, y + h - 2, Paints.cutLine)
        val qr = h - 20f
        val modules = QrCodes.matrix(QrCodes.payloadFor(label.objectId), margin = 1)
        val cell = qr / modules.size
        val qx = x + 10f
        val qy = y + 10f
        modules.forEachIndexed { r, row ->
            row.forEachIndexed { c, on ->
                if (on) canvas.drawRect(qx + c * cell, qy + r * cell, qx + (c + 1) * cell, qy + (r + 1) * cell, Paints.black)
            }
        }
        val textX = qx + qr + 8f
        val textWidth = x + w - 8f - textX
        canvas.drawText(ellipsize(label.name, Paints.name, textWidth), textX, y + h / 2 - 2, Paints.name)
        if (label.place.isNotBlank()) {
            canvas.drawText(ellipsize(label.place, Paints.place, textWidth), textX, y + h / 2 + 12, Paints.place)
        }
        canvas.drawText("Object Finder", textX, y + h - 12, Paints.brand)
    }

    private fun ellipsize(text: String, paint: TextPaint, width: Float) =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private object Paints {
        val black = Paint().apply { color = Color.BLACK }
        val cutLine = Paint().apply { color = Color.rgb(225, 225, 225); style = Paint.Style.STROKE; strokeWidth = 0.5f }
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val place = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; color = Color.rgb(60, 60, 60) }
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 6.5f; color = Color.rgb(140, 140, 140) }
    }
}

/** Crea il PDF in cache e lo stampa (servizio di stampa di Android) o lo condivide. */
object LabelSheetActions {
    private fun file(context: Context): File {
        val dir = File(context.cacheDir, "labels").apply { mkdirs() }
        return File(dir, "etichette-object-finder.pdf")
    }

    fun create(context: Context, labels: List<SheetLabel>): File =
        file(context).also { f -> FileOutputStream(f).use { LabelSheetPdf.write(labels, it) } }

    fun print(context: Context, pdf: File, count: Int) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print("Etichette Object Finder ($count)", PdfFileAdapter(pdf), PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build())
    }

    /** false se nessuna app può ricevere il PDF: il messaggio lo mostra chi chiama. */
    fun share(context: Context, pdf: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Etichette Object Finder")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(send, "Condividi etichette"))
            return true
        } catch (_: ActivityNotFoundException) {
            return false
        }
    }

    /** Passa un PDF già pronto al servizio di stampa così com'è. */
    private class PdfFileAdapter(private val pdf: File) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback, extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder(pdf.name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            runCatching {
                pdf.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
                .onFailure { callback.onWriteFailed(it.message) }
        }
    }
}
