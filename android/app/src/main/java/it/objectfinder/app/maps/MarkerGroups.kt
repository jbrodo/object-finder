package it.objectfinder.app.maps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import it.objectfinder.app.data.Geo

/**
 * Oggetti nello stesso punto (entro l'incertezza del GPS) diventano un solo segnaposto con il
 * conteggio: altrimenti si sovrappongono e se ne vede uno solo.
 */
data class MarkerGroup(val latitude: Double, val longitude: Double, val items: List<MapMarker>) {
    val key: String get() = items.first().id
    val label: String get() = if (items.size == 1) items[0].title else "${items.size} oggetti"
    /** Tutti i punti del gruppo sono approssimati: il segnaposto lo mostra (stile vuoto). */
    val approximate: Boolean get() = items.all { it.approximate }
    /** Raggio del cerchio di incertezza: la peggiore tra le posizioni GPS del gruppo. */
    val uncertaintyMeters: Float? get() = items.mapNotNull { it.accuracyMeters }.maxOrNull()
}

/** Raggruppamento greedy: ogni segnaposto entra nel primo gruppo entro [radiusMeters] dal suo capostipite. */
fun groupMarkers(markers: List<MapMarker>, radiusMeters: Double = 15.0): List<MarkerGroup> {
    val groups = mutableListOf<MutableList<MapMarker>>()
    markers.forEach { marker ->
        val home = groups.firstOrNull { group ->
            val first = group.first()
            Geo.distanceMeters(first.latitude, first.longitude, marker.latitude, marker.longitude) <= radiusMeters
        }
        if (home != null) home += marker else groups += mutableListOf(marker)
    }
    return groups.map { group -> MarkerGroup(group.first().latitude, group.first().longitude, group.toList()) }
}

/**
 * Etichetta-segnaposto con il nome (o il conteggio) disegnata come bitmap, uguale per Google Maps
 * e OpenStreetMap. Il punto esatto è la punta in basso al centro (ancora 0.5, 1.0).
 */
fun labelPinBitmap(
    text: String,
    background: Int,
    foreground: Int,
    density: Float,
    /** Posizione approssimata: etichetta vuota (fondo chiaro, bordo e testo colorati). */
    outlined: Boolean = false,
    surface: Int = android.graphics.Color.WHITE,
): Bitmap {
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (outlined) background else foreground
        textSize = 13f * density
        typeface = Typeface.DEFAULT_BOLD
    }
    val maxTextWidth = 160f * density
    val label = TextUtils.ellipsize(text, textPaint, maxTextWidth, TextUtils.TruncateAt.END).toString()
    val padH = 10f * density
    val padV = 6f * density
    val pointer = 7f * density
    val textWidth = textPaint.measureText(label)
    val metrics = textPaint.fontMetrics
    val boxHeight = (metrics.descent - metrics.ascent) + 2 * padV
    val width = (textWidth + 2 * padH).toInt().coerceAtLeast((boxHeight).toInt())
    val height = (boxHeight + pointer).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (outlined) surface else background }
    val radius = boxHeight / 2
    val box = RectF(0f, 0f, width.toFloat(), boxHeight)
    canvas.drawRoundRect(box, radius, radius, fill)
    if (outlined) {
        val stroke = 2f * density
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background
            style = Paint.Style.STROKE
            strokeWidth = stroke
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
        }
        val inset = stroke / 2
        canvas.drawRoundRect(RectF(inset, inset, width - inset, boxHeight - inset), radius - inset, radius - inset, border)
    }
    val tip = Path().apply {
        moveTo(width / 2f - pointer, boxHeight - 1)
        lineTo(width / 2f + pointer, boxHeight - 1)
        lineTo(width / 2f, height.toFloat())
        close()
    }
    canvas.drawPath(tip, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
    canvas.drawText(label, (width - textWidth) / 2f, padV - metrics.ascent, textPaint)
    return bitmap
}
