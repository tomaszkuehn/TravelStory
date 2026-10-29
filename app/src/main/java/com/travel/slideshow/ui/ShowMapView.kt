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
import com.travel.slideshow.model.PhotoItem
import com.travel.slideshow.viewmodel.RouteState
import com.travel.slideshow.viewmodel.ShowStep
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
fun ShowMap(step: ShowStep.WorldCountry, onMapTap: () -> Unit, onGesture: () -> Unit, manual: () -> Boolean, modifier: Modifier = Modifier) {
    SingleMapView(step, onMapTap, onGesture, manual, modifier) { mapView -> drawWorldCountry(mapView, step, manual) }
}

@Composable
fun ShowMap(step: ShowStep.LocationMap, pastRoutes: List<RouteState>, onMapTap: () -> Unit, onGesture: () -> Unit, manual: () -> Boolean, modifier: Modifier = Modifier) {
    SingleMapView(step, onMapTap, onGesture, manual, modifier) { mapView -> drawLocation(mapView, step.photo, pastRoutes, manual) }
}

@Composable
fun ShowMap(
    step: ShowStep.RouteMap,
    routePts: List<List<Double>>?,
    pastRoutes: List<RouteState>,
    totalSec: Float,
    runId: Int,
    onMapTap: () -> Unit,
    onGesture: () -> Unit,
    manual: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val key = listOf(step.photo.uri, routePts?.size ?: -1)
    val startedAt = remember(runId, step.photo.uri) { System.currentTimeMillis() }
    SingleMapView(key, onMapTap, onGesture, manual, modifier) {
        mapView -> drawRoute(mapView, step, routePts, pastRoutes, totalSec, System.currentTimeMillis() - startedAt, manual)
    }
}

@Composable
private fun SingleMapView(key: Any, onMapTap: () -> Unit, onGesture: () -> Unit, manual: () -> Boolean, modifier: Modifier, effect: suspend (MapView) -> Unit) {
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
                setTileSource(TileSourceFactory.MAPNIK)
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
        }
    )
    val mv = ref.value
    DisposableEffect(Unit) { onDispose { ref.value = null } }
    LaunchedEffect(key, mv) {
        mv?.let { effect(it) }
    }
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

private class AirplaneTipOverlay : Overlay() {
    var tip: GeoPoint? = null
    var bearingDeg = 0.0
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
        canvas.restore()
    }
}

private fun redPinDrawable(mapView: MapView): BitmapDrawable {
    val bmp = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    c.drawCircle(36f, 26f, 17f, pin)
    val tail = Path()
    tail.moveTo(21f, 32f)
    tail.lineTo(51f, 32f)
    tail.lineTo(36f, 64f)
    tail.close()
    c.drawPath(tail, pin)
    c.drawCircle(36f, 26f, 6.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
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

private suspend fun drawWorldCountry(mapView: MapView, step: ShowStep.WorldCountry, manual: () -> Boolean) {
    val country = step.country
    clearOverlays(mapView)
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
        mapView.zoomToBoundingBox(bbox, false)
        val fitZoom = (mapView.zoomLevelDouble - 0.4).coerceIn(2.5, 10.0)
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(15.0, 0.0))
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
        val line = Polyline(mapView)
        line.setPoints(past.points.map { GeoPoint(it[1], it[0]) })
        line.outlinePaint.color = 0xFF29B6F6.toInt()
        line.outlinePaint.strokeWidth = 6.0f
        line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        line.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        mapView.overlays.add(line)
    }
}

private suspend fun drawLocation(mapView: MapView, photo: PhotoItem, pastRoutes: List<RouteState>, manual: () -> Boolean) {
    val lat = photo.lat ?: return
    val lon = photo.lon ?: return
    clearOverlays(mapView)
    addPastRouteLines(mapView, pastRoutes, photo.uri)
    val geo = GeoPoint(lat, lon)
    mapView.overlays.add(
        Marker(mapView).apply {
            position = geo
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = (photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (photo.countryName ?: "")
        }
    )
    waitManualHeld(manual)
    mapView.controller.setZoom(4.0)
    mapView.controller.setCenter(geo)
    animateZoomDecel(mapView, geo, 15.5, 1700L, manual)
    mapView.invalidate()
}

private suspend fun animateZoomDecel(
    mapView: MapView,
    target: GeoPoint,
    targetZoom: Double,
    durationMs: Long,
    manual: () -> Boolean = { false },
) {
    val startZoom = mapView.zoomLevelDouble
    val startLat = mapView.mapCenter.latitude
    val startLon = mapView.mapCenter.longitude
    val frames = (durationMs / 16).coerceAtLeast(1)
    for (i in 1..frames) {
        if (manual()) return
        val t = i.toDouble() / frames
        val eased = 1 - Math.pow(1 - t, 3.0)
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
    routePts: List<List<Double>>?,
    pastRoutes: List<RouteState>,
    totalSec: Float,
    elapsedMs: Long,
    manual: () -> Boolean,
) {
    val from = step.from
    val to = step.photo
    val aLat = from.lat ?: return
    val aLon = from.lon ?: return
    val bLat = to.lat ?: return
    val bLon = to.lon ?: return
    clearOverlays(mapView)

    addPastRouteLines(mapView, pastRoutes, to.uri)

    val full = (routePts ?: listOf(listOf(aLon, aLat), listOf(bLon, bLat)))
        .map { GeoPoint(it[1], it[0]) }
    val line = Polyline(mapView)
    line.setPoints(full)
    line.outlinePaint.color = 0xFF29B6F6.toInt()
    line.outlinePaint.strokeWidth = 6.0f
    line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    line.outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
    mapView.overlays.add(line)

    mapView.overlays.add(
        Marker(mapView).apply {
            position = GeoPoint(aLat, aLon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = from.name
        }
    )

    var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
    for (p in full) {
        w = minOf(w, p.longitude)
        e = maxOf(e, p.longitude)
        s = minOf(s, p.latitude)
        n = maxOf(n, p.latitude)
    }
    val bb = BoundingBox(n, e, s, w)

    val startGeo = GeoPoint(aLat, aLon)
    mapView.controller.setZoom(14.2)
    mapView.controller.setCenter(startGeo)
    mapView.invalidate()
    waitManualHeld(manual)

    val totalMs = (totalSec * 1000).toLong().coerceAtLeast(2500)
    val elapsed = elapsedMs.coerceAtLeast(0)
    val remaining = (totalMs - elapsed).coerceAtLeast(400)
    val zoomInMs = (remaining * 0.28).coerceIn(500.0, 2600.0).toLong()
    val holdMs = minOf(300L, remaining / 4)
    val drawMs = (remaining - holdMs - zoomInMs).coerceAtLeast(0)

    val plane = AirplaneTipOverlay()
    mapView.overlays.add(plane)
    kotlinx.coroutines.delay(holdMs)

    if (full.size >= 2) {
        val steps = (drawMs / 40).coerceIn(4L, 900L)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val frac = t * t * (3 - 2 * t)
            val posF = frac * (full.size - 1)
            val idx = posF.toInt()
            val partial = ArrayList<GeoPoint>(idx + 2)
            for (k in 0..idx) partial.add(full[k])
            if (idx + 1 <= full.size - 1) {
                val f = posF - idx
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
        plane.tip = null
        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(bLat, bLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = redPinDrawable(mapView)
                title = (to.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (to.countryName ?: "")
            }
        )
        mapView.invalidate()
    }

    animateZoomDecel(mapView, GeoPoint(bLat, bLon), 15.5, zoomInMs, manual)
}