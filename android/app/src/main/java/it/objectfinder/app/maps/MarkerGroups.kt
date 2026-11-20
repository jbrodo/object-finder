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
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

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
 * Scala della mappa (Web Mercator, tile da 256 dp come Google Maps e osmdroid scalato in dp):
 * serve a raggruppare per distanza sullo schermo invece che in metri fissi.
 */
object MapScale {
    /** Distanza sullo schermo sotto cui due segnaposti si sovrappongono (etichette comprese). */
    const val CLUSTER_DP = 56.0
    /** Zoom massimo utile dei tile OSM: oltre, i punti non si separano più. */
    const val MAX_ZOOM = 19.0
    /** Raggio del ventaglio per i punti che restano insieme anche allo zoom massimo. */
    const val SPIDER_DP = 72.0

    fun metersPerDp(latitude: Double, zoom: Double): Double =
        156_543.03392 * cos(Math.toRadians(latitude)) / 2.0.pow(zoom)

    fun clusterRadiusMeters(latitude: Double, zoom: Double): Double = CLUSTER_DP * metersPerDp(latitude, zoom)

    /** Zoom arrotondato a mezzo livello: si ricalcola il raggruppamento solo quando serve davvero. */
    fun step(zoom: Double): Double = kotlin.math.round(zoom * 2) / 2
}

/** Massima distanza tra due oggetti del gruppo (in metri). */
fun MarkerGroup.spreadMeters(): Double {
    var max = 0.0
    for (i in items.indices) for (j in i + 1 until items.size) {
        val d = Geo.distanceMeters(items[i].latitude, items[i].longitude, items[j].latitude, items[j].longitude)
        if (d > max) max = d
    }
    return max
}

/** Zoomando ancora si separano? Altrimenti sono "nello stesso punto" e vanno aperti a ventaglio. */
fun MarkerGroup.canSplitByZooming(currentZoom: Double): Boolean =
    currentZoom < MapScale.MAX_ZOOM && spreadMeters() > MapScale.clusterRadiusMeters(latitude, MapScale.MAX_ZOOM)

/** Un oggetto del ventaglio: posizione solo visiva attorno al punto vero del gruppo. */
data class SpiderLeg(val item: MapMarker, val latitude: Double, val longitude: Double)

/**
 * Ventaglio: oggetti disposti su un cerchio attorno al punto del gruppo, il primo in alto, poi in
 * senso orario. Il raggio cresce con il numero di oggetti per non sovrapporre le etichette.
 */
fun spiderLegs(group: MarkerGroup, zoom: Double): List<SpiderLeg> {
    val n = group.items.size
    val radiusDp = maxOf(MapScale.SPIDER_DP, n * 16.0)
    val radiusMeters = radiusDp * MapScale.metersPerDp(group.latitude, zoom)
    val metersPerDegLat = 111_320.0
    val metersPerDegLon = metersPerDegLat * cos(Math.toRadians(group.latitude))
    return group.items.mapIndexed { i, item ->
        val angle = -Math.PI / 2 + 2 * Math.PI * i / n
        val east = radiusMeters * cos(angle)
        val north = -radiusMeters * sin(angle) // angolo in coordinate schermo (y verso il basso)
        SpiderLeg(item, group.latitude + north / metersPerDegLat, group.longitude + east / metersPerDegLon)
    }
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
