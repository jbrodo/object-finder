package it.objectfinder.app.qr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Formato etichetta: `objectfinder://object/<uuid>`. Contiene solo l'id (nessun dato sensibile):
 * chi la scansiona senza accesso al progetto non ottiene informazioni sull'oggetto.
 */
object QrCodes {
    const val SCHEME = "objectfinder"
    const val HOST = "object"
    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun payloadFor(objectId: String): String = "$SCHEME://$HOST/$objectId"

    /** Estrae l'id oggetto da un contenuto scansionato; null se non è un'etichetta Object Finder. */
    fun parseObjectId(raw: String?): String? {
        val value = raw?.trim() ?: return null
        val prefix = "$SCHEME://$HOST/"
        if (!value.startsWith(prefix, ignoreCase = true)) return null
        val id = value.substring(prefix.length).substringBefore('?').substringBefore('#').trimEnd('/')
        return id.takeIf { UUID_REGEX.matches(it) }?.lowercase()
    }

    /** Matrice QR (true = modulo scuro), con quiet zone di [margin] moduli. */
    fun matrix(payload: String, margin: Int = 2): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin,
        )
        val bits = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints)
        return Array(bits.height) { y -> BooleanArray(bits.width) { x -> bits[x, y] } }
    }

    /** Etichetta stampabile: QR + nome oggetto sotto, sfondo bianco. */
    fun labelBitmap(objectId: String, title: String, sizePx: Int = 900): Bitmap {
        val modules = matrix(payloadFor(objectId))
        val cell = sizePx / modules.size
        val qrSide = cell * modules.size
        val textArea = sizePx / 5
        val bitmap = Bitmap.createBitmap(qrSide, qrSide + textArea, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val dark = Paint().apply { color = Color.BLACK; isAntiAlias = false }
        modules.forEachIndexed { y, row ->
            row.forEachIndexed { x, on ->
                if (on) canvas.drawRect(
                    (x * cell).toFloat(), (y * cell).toFloat(),
                    ((x + 1) * cell).toFloat(), ((y + 1) * cell).toFloat(), dark,
                )
            }
        }
        val text = TextPaint().apply {
            color = Color.BLACK
            isAntiAlias = true
            textSize = textArea * 0.42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val maxWidth = qrSide - 2f * cell * 2
        val line = TextUtils.ellipsize(title, text, maxWidth, TextUtils.TruncateAt.END).toString()
        canvas.drawText(line, qrSide / 2f, qrSide + textArea * 0.55f, text)
        return bitmap
    }
}
