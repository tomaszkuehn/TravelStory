package com.travel.slideshow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.travel.slideshow.data.geo.CountryInfo
import com.travel.slideshow.model.MapStyle
import com.travel.slideshow.model.TravelMode
import com.travel.slideshow.viewmodel.PinState
import com.travel.slideshow.viewmodel.RouteState
import com.travel.slideshow.viewmodel.ShowStep
import com.travel.slideshow.viewmodel.lengthKm
import com.travel.slideshow.viewmodel.pinKey
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun ShowMap(
    step: ShowStep,
    route: RouteState?,
    pastRoutes: List<RouteState>,
    visitedPins: List<PinState>,
    pinNumbers: Map<String, Int>,
    travelMode: TravelMode,
    totalSec: Float,
    runId: Int,
    onMapTap: () -> Unit,
    onGesture: () -> Unit,
    manual: () -> Boolean,
    onArrived: (ShowStep) -> Unit,
    mapStyle: MapStyle,
    onRouteProgress: (RouteProgress?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The effect restarts when the OSRM route arrives, but elapsed time is measured from the
    // start of the step so the route animation still fits the segment.
    val key: Any = when (step) {
        is ShowStep.RouteMap -> listOf(step, route?.points?.size ?: -1, route?.flight)
        else -> step
    }
    SingleMapView(key, step, mapStyle, onMapTap, onGesture, manual, modifier) { mv, elapsedMs ->
        pinNumberMaps[mv] = pinNumbers
        if (step !is ShowStep.RouteMap) onRouteProgress(null)
        when (step) {
            is ShowStep.WorldCountry -> drawWorldCountry(mv, step, visitedPins, manual)
            is ShowStep.LocationMap -> drawLocation(mv, step, pastRoutes, visitedPins, totalSec, elapsedMs, manual, { onArrived(step) })
            is ShowStep.RouteMap ->
                drawRoute(
                    mv, step, route, pastRoutes, visitedPins, travelMode, totalSec, elapsedMs, manual,
                    { onArrived(step) }, onRouteProgress,
                )
            is ShowStep.Finished -> drawFinale(mv, pastRoutes, visitedPins)
            else -> {}
        }
    }
}

@Composable
private fun SingleMapView(key: Any, startKey: Any, mapStyle: MapStyle, onMapTap: () -> Unit, onGesture: () -> Unit, manual: () -> Boolean, modifier: Modifier, effect: suspend (MapView, Long) -> Unit) {
    val context = LocalContext.current
    val tapHandler by rememberUpdatedState(onMapTap)
    val gestureHandler by rememberUpdatedState(onGesture)
    val tapOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                tapHandler()
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
    }
    val ref = remember { mutableStateOf<GesturingMapView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GesturingMapView(ctx).apply {
                setTileSource(tileSourceFor(mapStyle))
                applyStyleFilter(this, mapStyle)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                minZoomLevel = 1.6
                maxZoomLevel = 18.0
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(2.0)
                controller.setCenter(GeoPoint(15.0, 0.0))
                onGestureSink = { gestureHandler() }
                tag = tapOverlay
                overlays.add(tapOverlay)
            }.also { ref.value = it }
        },
        update = { mv ->
            val src = tileSourceFor(mapStyle)
            if (mv.tileProvider.tileSource.name() != src.name()) mv.setTileSource(src)
            applyStyleFilter(mv, mapStyle)
        },
    )
    val mv = ref.value
    val startedAt = remember(startKey) { System.currentTimeMillis() }
    DisposableEffect(Unit) { onDispose { ref.value = null } }
    LaunchedEffect(key, mv) {
        mv?.let { effect(it, System.currentTimeMillis() - startedAt) }
    }
}

/** Distance shown in the corner while a leg is drawn. */
data class RouteProgress(val uri: Any, val legKm: Double, val doneKm: Double, val flight: Boolean)

/** Esri World Imagery (no key); its tile URLs are z/y/x rather than the usual z/x/y. */
private object EsriImagery : OnlineTileSourceBase(
    "EsriWorldImagery", 0, 19, 256, ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" + MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex)
}

/** The light and dark styles are the standard OSM tiles recoloured on the phone. */
private fun tileSourceFor(style: MapStyle): ITileSource = when (style) {
    MapStyle.STANDARD, MapStyle.LIGHT, MapStyle.DARK -> TileSourceFactory.MAPNIK
    MapStyle.TOPO -> TileSourceFactory.OpenTopo
    MapStyle.SATELLITE -> EsriImagery
}

private val LUM = floatArrayOf(0.2126f, 0.7152f, 0.0722f)

/** Mostly grey, washed-out map that keeps a hint of the original colours. */
private val LIGHT_FILTER: ColorMatrixColorFilter by lazy {
    val keep = 0.3f
    val gain = 0.72f
    val lift = 255f * 0.28f
    val m = FloatArray(20)
    for (r in 0..2) {
        for (c in 0..2) m[r * 5 + c] = gain * (LUM[c] * (1 - keep) + if (r == c) keep else 0f)
        m[r * 5 + 4] = lift
    }
    m[18] = 1f
    ColorMatrixColorFilter(ColorMatrix(m))
}

/** Inverted grey with a slight blue cast: dark land, lighter roads and borders. */
private val DARK_FILTER: ColorMatrixColorFilter by lazy {
    val gain = -0.82f
    val base = floatArrayOf(222f, 228f, 242f)
    val m = FloatArray(20)
    for (r in 0..2) {
        for (c in 0..2) m[r * 5 + c] = gain * LUM[c]
        m[r * 5 + 4] = base[r]
    }
    m[18] = 1f
    ColorMatrixColorFilter(ColorMatrix(m))
}

private val appliedStyle = java.util.WeakHashMap<MapView, MapStyle>()

private fun applyStyleFilter(mv: MapView, style: MapStyle) {
    if (appliedStyle[mv] == style) return
    appliedStyle[mv] = style
    mv.overlayManager.tilesOverlay.setColorFilter(
        when (style) {
            MapStyle.LIGHT -> LIGHT_FILTER
            MapStyle.DARK -> DARK_FILTER
            else -> null
        }
    )
    mv.invalidate()
}

/**
 * Closing map: every leg and numbered pin of the trip, zooming from wherever the map was to the
 * whole trail, which sits in the upper part of the screen above the statistics.
 */
private suspend fun drawFinale(mapView: MapView, pastRoutes: List<RouteState>, visitedPins: List<PinState>) {
    clearOverlays(mapView)
    addPastRouteLines(mapView, pastRoutes, null)
    addVisitedPins(mapView, visitedPins)
    mapView.invalidate()
    val pts = visitedPins.map { it.lat to it.lon } +
        pastRoutes.flatMap { r -> r.points.map { it[1] to it[0] } }
    if (pts.isEmpty()) return
    var n = pts.maxOf { it.first }; var s = pts.minOf { it.first }
    var e = pts.maxOf { it.second }; var w = pts.minOf { it.second }
    // A single place still gets a town-sized view rather than maximum zoom.
    if (n - s < 0.02) { n += 0.01; s -= 0.01 }
    if (e - w < 0.02) { e += 0.01; w -= 0.01 }
    val bb = BoundingBox(n, e, s, w)
    // Wait for the view to be laid out; zoomToBoundingBox needs its size.
    while (mapView.width == 0 || mapView.height == 0) kotlinx.coroutines.delay(16)
    val startZoom = mapView.zoomLevelDouble
    val startCenter = GeoPoint(mapView.mapCenter.latitude, mapView.mapCenter.longitude)
    mapView.visibility = android.view.View.INVISIBLE
    val h = mapView.height
    mapView.zoomToBoundingBox(bb, false, (minOf(mapView.width, h) * 0.12).toInt())
    // Shrink a bit more and push the trail up so the bottom panel does not cover it.
    mapView.controller.setZoom((mapView.zoomLevelDouble - 0.7).coerceIn(2.0, 15.0))
    mapView.controller.setCenter(bb.centerWithDateLine)
    val shifted = mapView.projection.fromPixels(mapView.width / 2, (h / 2 + h * 0.2).toInt())
    val fitZoom = mapView.zoomLevelDouble
    mapView.controller.setZoom(startZoom)
    mapView.controller.setCenter(startCenter)
    mapView.visibility = android.view.View.VISIBLE
    mapView.invalidate()
    kotlinx.coroutines.delay(300)
    animateZoomDecel(mapView, GeoPoint(shifted.latitude, shifted.longitude), fitZoom, 3200L)
    mapView.invalidate()
}

private class GesturingMapView(context: Context) : MapView(context) {
    var onGestureSink: (() -> Unit)? = null
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> onGestureSink?.invoke()
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * Symbol at the tip of the line being drawn: a plane on flights, otherwise the chosen way of travel
 * (a car seen from above, turned along the road; a bike or walker badge).
 */
private class TravelTipOverlay(private val mode: TravelMode, private val flight: Boolean) : Overlay() {
    var tip: GeoPoint? = null
    var bearingDeg = 0.0
    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 40, 55)
        style = Paint.Style.FILL
    }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 24f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(30, 40, 55)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }

    override fun draw(canvas: Canvas, projection: org.osmdroid.views.Projection) {
        val t = tip ?: return
        val px = projection.toPixels(t, null) ?: return
        canvas.save()
        canvas.translate(px.x.toFloat(), px.y.toFloat())
        when {
            flight -> drawPlane(canvas)
            mode == TravelMode.CAR -> drawCar(canvas)
            else -> drawBadge(canvas, if (mode == TravelMode.BIKE) "\uD83D\uDEB2" else "\uD83D\uDEB6")
        }
        canvas.restore()
    }

    /** Car from above, nose pointing along the bearing. */
    private fun drawCar(canvas: Canvas) {
        canvas.rotate(bearingDeg.toFloat())
        canvas.scale(CAR_SCALE, CAR_SCALE)
        // Wheels peek out from under the body.
        for (y in floatArrayOf(-11f, 8f)) {
            canvas.drawRoundRect(-11f, y, -7f, y + 7f, 1.5f, 1.5f, dark)
            canvas.drawRoundRect(7f, y, 11f, y + 7f, 1.5f, 1.5f, dark)
        }
        canvas.drawRoundRect(-9f, -19f, 9f, 18f, 6f, 6f, fill)
        canvas.drawRoundRect(-9f, -19f, 9f, 18f, 6f, 6f, stroke)
        val windshield = Path().apply {
            moveTo(-7f, -6f); lineTo(7f, -6f); lineTo(5.5f, -11f); lineTo(-5.5f, -11f); close()
        }
        canvas.drawPath(windshield, dark)
        canvas.drawRoundRect(-6f, 9f, 6f, 13f, 1.5f, 1.5f, dark)
        // Headlights.
        canvas.drawCircle(-5f, -16.5f, 1.6f, dark)
        canvas.drawCircle(5f, -16.5f, 1.6f, dark)
    }

    /** Upright emoji in a white disc (bike/walker icons don't read well when rotated). */
    private fun drawBadge(canvas: Canvas, emoji: String) {
        canvas.drawCircle(0f, 0f, 18f, fill)
        canvas.drawCircle(0f, 0f, 18f, stroke)
        val fm = badgeText.fontMetrics
        canvas.drawText(emoji, 0f, -(fm.ascent + fm.descent) / 2, badgeText)
    }

    private fun drawPlane(canvas: Canvas) {
        canvas.rotate(bearingDeg.toFloat())
        val path = Path()
        path.moveTo(0f, -22f)
        path.lineTo(3.5f, -9f)
        path.lineTo(19f, -2f)
        path.lineTo(19f, 3f)
        path.lineTo(3.5f, 3f)
        path.lineTo(2.5f, 9f)
        path.lineTo(10f, 16f)
        path.lineTo(10f, 19f)
        path.lineTo(1f, 16.5f)
        path.lineTo(-1f, 16.5f)
        path.lineTo(-10f, 19f)
        path.lineTo(-10f, 16f)
        path.lineTo(-2.5f, 9f)
        path.lineTo(-3.5f, 3f)
        path.lineTo(-19f, 3f)
        path.lineTo(-19f, -2f)
        path.lineTo(-3.5f, -9f)
        path.close()
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}

/** Car tip symbol size relative to its drawn geometry (≈ 56 px long at 1.5). */
private const val CAR_SCALE = 1.5f

/** Pause on the place after the zoom-in, before the photos. */
private const val LOCATION_HOLD_MS = 1000L

private const val PIN_RED = 0xFFE53935.toInt()
/** Pin bitmap geometry (px): head centre and radius, tip, bitmap height (the anchor is its bottom). */
private const val PIN_BMP = 72f
private const val PIN_HEAD_Y = 26f
private const val PIN_HEAD_R = 17f
private const val PIN_TOP = PIN_HEAD_Y - PIN_HEAD_R
private const val PIN_TIP = 64f

private val pinNumberMaps = java.util.WeakHashMap<MapView, Map<String, Int>>()

/**
 * Draws the visit order inside the heads of numbered pins. Digits are about half the pin's height
 * when zoomed in and shrink when zoomed out, where pins crowd together; wide numbers stretch the head.
 */
private class PinNumbersOverlay : Overlay() {
    val items = ArrayList<Pair<GeoPoint, Int>>()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_RED }
    private val pt = android.graphics.Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || items.isEmpty()) return
        val f = ((mapView.zoomLevelDouble - 4.0) / 8.0).coerceIn(0.65, 1.0).toFloat()
        // Digit height is ~0.72 of the text size.
        text.textSize = (PIN_TIP - PIN_TOP) * 0.5f / 0.72f * f
        val fm = text.fontMetrics
        val pj = mapView.projection
        for ((g, n) in items) {
            pj.toPixels(g, pt)
            val cx = pt.x.toFloat()
            val cy = pt.y - (PIN_BMP - PIN_HEAD_Y)
            val label = n.toString()
            val half = maxOf(PIN_HEAD_R, text.measureText(label) / 2 + 5f)
            if (half > PIN_HEAD_R) {
                c.drawRoundRect(cx - half, cy - PIN_HEAD_R, cx + half, cy + PIN_HEAD_R, PIN_HEAD_R, PIN_HEAD_R, bg)
            }
            c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, text)
        }
    }
}

/** Red pin; a numbered one (visit order from the show) gets its number in the head instead of the dot. */
private fun addPin(mapView: MapView, lat: Double, lon: Double, title: String?, icon: BitmapDrawable? = null) {
    val number = pinNumberMaps[mapView]?.get(pinKey(lat, lon))
    val marker = Marker(mapView).apply {
        position = GeoPoint(lat, lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        this.icon = if (number == null) icon ?: redPinDrawable(mapView) else redPinDrawable(mapView, dot = false)
        this.title = title
    }
    var numbers = mapView.overlays.filterIsInstance<PinNumbersOverlay>().firstOrNull()
    if (numbers == null && number != null) {
        numbers = PinNumbersOverlay()
        mapView.overlays.add(numbers)
    }
    // Pins go under the numbers overlay so every number stays on top.
    if (numbers != null) mapView.overlays.add(mapView.overlays.indexOf(numbers), marker)
    else mapView.overlays.add(marker)
    if (number != null) numbers?.items?.add(GeoPoint(lat, lon) to number)
}

private fun redPinDrawable(mapView: MapView, dot: Boolean = true): BitmapDrawable {
    val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PIN_RED }
    c.drawCircle(36f, 26f, 17f, pin)
    val tail = Path()
    tail.moveTo(21f, 32f)
    tail.lineTo(51f, 32f)
    tail.lineTo(36f, 64f)
    tail.close()
    c.drawPath(tail, pin)
    if (dot) c.drawCircle(36f, 26f, 6.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
    return BitmapDrawable(mapView.context.resources, bmp)
}

private fun clearOverlays(mapView: MapView) {
    val keepOverlay = mapView.tag as? MapEventsOverlay
    mapView.overlays.clear()
    if (keepOverlay != null) mapView.overlays.add(keepOverlay)
}

private suspend fun waitManualHeld(manual: () -> Boolean) {
    while (manual()) kotlinx.coroutines.delay(80)
}

private suspend fun drawWorldCountry(mapView: MapView, step: ShowStep.WorldCountry, visitedPins: List<PinState>, manual: () -> Boolean) {
    val country = step.country
    clearOverlays(mapView)
    addVisitedPins(mapView, visitedPins)
    for (ring in country.outerRings) {
        val pts = ring.map { GeoPoint(it[1], it[0]) }
        val poly = Polygon(mapView).apply {
            points = pts
            fillColor = Color.argb(80, 255, 152, 0)
            outlinePaint.color = Color.argb(255, 255, 152, 0)
            outlinePaint.strokeWidth = 2.5f
        }
        mapView.overlays.add(poly)
    }
    if (step.zoomedIn) {
        val bbox = BoundingBox(
            country.bbox.north, country.bbox.east, country.bbox.south, country.bbox.west
        )
        waitManualHeld(manual)
        mapView.visibility = android.view.View.INVISIBLE
        mapView.zoomToBoundingBox(bbox, false)
        val fitZoom = (mapView.zoomLevelDouble - 0.4).coerceIn(2.5, 10.0)
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(15.0, 0.0))
        mapView.visibility = android.view.View.VISIBLE
        mapView.invalidate()
        val center = bbox.centerWithDateLine
        animateZoomDecel(mapView, GeoPoint(center.latitude, center.longitude), fitZoom, 1800L, manual)
        val (clat, clon) = country.mainPolyCentroid
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(clat, clon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = CountryInfo.flagEmoji(country.iso3) + " " + country.name
            }
        )
    } else {
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(15.0, 0.0))
        mapView.invalidate()
    }
}

private fun addPastRouteLines(mapView: MapView, pastRoutes: List<RouteState>, skipUri: Any?) {
    for (past in pastRoutes) {
        if (past.uri == skipUri) continue
        if (past.points.size < 2) continue
        val line = routeLine(mapView, past.flight)
        line.setPoints(past.points.map { GeoPoint(it[1], it[0]) })
        mapView.overlays.add(line)
        addOffRoad(mapView, past.head)
        addOffRoad(mapView, past.tail)
    }
}

/** Dashed bit between a photo pin and the road (photo taken off the road network). */
private fun addOffRoad(mapView: MapView, pts: List<List<Double>>) {
    if (pts.size < 2) return
    val line = routeLine(mapView, flight = true)
    line.setPoints(pts.map { GeoPoint(it[1], it[0]) })
    mapView.overlays.add(line)
}

/** Road legs are solid; flights (long hops, no road connection) are dashed. */
private fun routeLine(mapView: MapView, flight: Boolean): Polyline {
    val line = Polyline(mapView)
    line.outlinePaint.color = 0xFF29B6F6.toInt()
    line.outlinePaint.strokeWidth = 6.0f
    line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    line.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    if (flight) line.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
    return line
}

private fun addVisitedPins(mapView: MapView, pins: List<PinState>) {
    if (pins.isEmpty()) return
    val sharedIcon = redPinDrawable(mapView)
    for (p in pins) addPin(mapView, p.lat, p.lon, p.title, sharedIcon)
}

private suspend fun drawLocation(
    mapView: MapView,
    step: ShowStep.LocationMap,
    pastRoutes: List<RouteState>,
    visitedPins: List<PinState>,
    totalSec: Float,
    elapsedMs: Long,
    manual: () -> Boolean,
    onArrived: () -> Unit,
) {
    val photo = step.photo
    val lat = photo.lat ?: return
    val lon = photo.lon ?: return
    clearOverlays(mapView)
    addPastRouteLines(mapView, pastRoutes, photo.uri)
    addVisitedPins(mapView, visitedPins.filterNot { it.lat == lat && it.lon == lon })
    val geo = GeoPoint(lat, lon)
    addPin(mapView, lat, lon, (photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (photo.countryName ?: ""))
    onArrived()
    waitManualHeld(manual)
    if (step.revisit) {
        mapView.controller.setZoom(15.5)
        mapView.controller.setCenter(geo)
        mapView.invalidate()
        return
    }
    mapView.controller.setZoom(4.0)
    mapView.controller.setCenter(geo)
    // The zoom fills the step and eases out gently; the photos follow LOCATION_HOLD_MS after it lands.
    val zoomMs = ((totalSec * 1000).toLong() - elapsedMs.coerceAtLeast(0) - LOCATION_HOLD_MS)
        .coerceAtLeast(1700L)
    animateZoomDecel(mapView, geo, 15.5, zoomMs, manual, easePower = 1.7)
    mapView.invalidate()
}

private suspend fun animateZoomDecel(
    mapView: MapView,
    target: GeoPoint,
    targetZoom: Double,
    durationMs: Long,
    manual: () -> Boolean = { false },
    /** Ease-out strength: 3 brakes hard near the end, values closer to 1 slow down only slightly. */
    easePower: Double = 3.0,
) {
    val startZoom = mapView.zoomLevelDouble
    val startLat = mapView.mapCenter.latitude
    val startLon = mapView.mapCenter.longitude
    val frames = (durationMs / 16).coerceAtLeast(1)
    for (i in 1..frames) {
        if (manual()) return
        val t = i.toDouble() / frames
        val eased = 1 - Math.pow(1 - t, easePower)
        mapView.controller.setZoom(startZoom + (targetZoom - startZoom) * eased)
        mapView.controller.setCenter(
            GeoPoint(startLat + (target.latitude - startLat) * eased,
                startLon + (target.longitude - startLon) * eased)
        )
        kotlinx.coroutines.delay(16)
    }
}

private suspend fun drawRoute(
    mapView: MapView,
    step: ShowStep.RouteMap,
    route: RouteState?,
    pastRoutes: List<RouteState>,
    visitedPins: List<PinState>,
    travelMode: TravelMode,
    totalSec: Float,
    elapsedMs: Long,
    manual: () -> Boolean,
    onArrived: () -> Unit,
    onProgress: (RouteProgress?) -> Unit,
) {
    val from = step.from
    val to = step.photo
    val aLat = from.lat ?: return
    val aLon = from.lon ?: return
    val bLat = to.lat ?: return
    val bLon = to.lon ?: return
    clearOverlays(mapView)

    addPastRouteLines(mapView, pastRoutes, to.uri)
    addVisitedPins(
        mapView,
        visitedPins.filterNot { (it.lat == aLat && it.lon == aLon) || (it.lat == bLat && it.lon == bLon) }
    )

    val full = (route?.points ?: listOf(listOf(aLon, aLat), listOf(bLon, bLat)))
        .map { GeoPoint(it[1], it[0]) }
    val line = routeLine(mapView, route?.flight == true)
    line.setPoints(listOf(full.first()))
    mapView.overlays.add(line)

    addPin(mapView, aLat, aLon, from.name)

    var w = minOf(aLon, bLon); var s = minOf(aLat, bLat); var e = maxOf(aLon, bLon); var n = maxOf(aLat, bLat)
    for (p in full) {
        w = minOf(w, p.longitude)
        e = maxOf(e, p.longitude)
        s = minOf(s, p.latitude)
        n = maxOf(n, p.latitude)
    }
    val bb = BoundingBox(n, e, s, w)

    // The map opens straight at the leg's travel zoom (no zoom-out first), centered on the start;
    // the zoom scales with the leg so the tip crosses the screen at the same pace on every leg.
    // While the route is still loading, the straight line between the two photos sets the zoom.
    val startGeo = GeoPoint(aLat, aLon)
    if (!manual()) {
        mapView.visibility = android.view.View.INVISIBLE
        mapView.zoomToBoundingBox(bb, false, 120)
        mapView.controller.setZoom((mapView.zoomLevelDouble - 0.2).coerceIn(3.0, 15.5))
        mapView.controller.setCenter(startGeo)
        mapView.visibility = android.view.View.VISIBLE
        mapView.invalidate()
    }
    waitManualHeld(manual)
    if (route == null) {
        onProgress(null)
        return
    }
    val legKm = route.lengthKm()
    onProgress(RouteProgress(to.uri, legKm, 0.0, route.flight))

    val totalMs = (totalSec * 1000).toLong().coerceAtLeast(2500)
    val elapsed = elapsedMs.coerceAtLeast(0)
    val remaining = (totalMs - elapsed).coerceAtLeast(400)
    val zoomInMs = (remaining * 0.28).coerceIn(500.0, 2600.0).toLong()
    val holdMs = minOf(500L, remaining / 6)
    val drawMs = (remaining - holdMs - zoomInMs).coerceAtLeast(0)

    addOffRoad(mapView, route.head)
    val plane = TravelTipOverlay(travelMode, route.flight)
    mapView.overlays.add(plane)
    kotlinx.coroutines.delay(holdMs)

    if (full.size >= 2) {
        // Progress by distance, not by point index: a simplified route has few points on straight
        // stretches and many in bends, which would make the tip speed up and slow down.
        val k = Math.cos(Math.toRadians((aLat + bLat) / 2)).coerceAtLeast(0.2)
        val cum = DoubleArray(full.size)
        for (j in 1 until full.size) {
            val dx = (full[j].longitude - full[j - 1].longitude) * k
            val dy = full[j].latitude - full[j - 1].latitude
            cum[j] = cum[j - 1] + Math.sqrt(dx * dx + dy * dy)
        }
        val total = cum.last()
        val steps = (drawMs / 40).coerceIn(4L, 900L)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val frac = t * t * (3 - 2 * t)
            val dist = frac * total
            var idx = java.util.Arrays.binarySearch(cum, dist).let { if (it >= 0) it else -it - 2 }
            idx = idx.coerceIn(0, full.size - 1)
            val partial = ArrayList<GeoPoint>(idx + 2)
            for (q in 0..idx) partial.add(full[q])
            if (idx + 1 <= full.size - 1) {
                val segLen = cum[idx + 1] - cum[idx]
                val f = if (segLen <= 0.0) 0.0 else (dist - cum[idx]) / segLen
                val a = full[idx]
                val b = full[idx + 1]
                partial.add(
                    GeoPoint(
                        a.latitude + (b.latitude - a.latitude) * f,
                        a.longitude + (b.longitude - a.longitude) * f
                    )
                )
            }
            if (partial.isNotEmpty()) {
                val tip = partial.last()
                plane.tip = tip
                plane.bearingDeg = (
                    if (partial.size >= 2) partial[partial.size - 2].bearingTo(tip)
                    else 0.0
                )
            }
            line.setPoints(partial)
            onProgress(RouteProgress(to.uri, legKm, legKm * frac, route.flight))
            if (!manual()) {
                val tip = partial.last()
                val cLat = mapView.mapCenter.latitude
                val cLon = mapView.mapCenter.longitude
                mapView.controller.setCenter(
                    GeoPoint(cLat + (tip.latitude - cLat) * 0.25, cLon + (tip.longitude - cLon) * 0.25)
                )
            }
            mapView.invalidate()
            kotlinx.coroutines.delay(if (drawMs <= 0) 0L else drawMs / steps)
        }
        line.setPoints(full)
        onProgress(RouteProgress(to.uri, legKm, legKm, route.flight))
        plane.tip = null
        addOffRoad(mapView, route.tail)
        addPin(mapView, bLat, bLon, (to.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (to.countryName ?: ""))
        onArrived()
        mapView.invalidate()
    }

    animateZoomDecel(mapView, GeoPoint(bLat, bLon), 15.5, zoomInMs, manual)
}