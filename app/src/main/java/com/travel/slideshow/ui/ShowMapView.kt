package com.travel.slideshow.ui

import android.graphics.Color
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
import com.travel.slideshow.viewmodel.ShowStep
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

@Composable
fun ShowMap(step: ShowStep.WorldCountry, onMapTap: () -> Unit, modifier: Modifier = Modifier) {
    SingleMapView(step, onMapTap, modifier) { mapView -> drawWorldCountry(mapView, step) }
}

@Composable
fun ShowMap(step: ShowStep.LocationMap, onMapTap: () -> Unit, modifier: Modifier = Modifier) {
    SingleMapView(step, onMapTap, modifier) { mapView -> drawLocation(mapView, step.photo) }
}

@Composable
fun ShowMap(
    step: ShowStep.RouteMap,
    routePts: List<List<Double>>?,
    totalSec: Float,
    runId: Int,
    onMapTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = listOf(step.photo.uri, routePts?.size ?: -1)
    val startedAt = remember(runId, step.photo.uri) { System.currentTimeMillis() }
    SingleMapView(key, onMapTap, modifier) {
        mapView -> drawRoute(mapView, step, routePts, totalSec, System.currentTimeMillis() - startedAt)
    }
}

@Composable
private fun SingleMapView(key: Any, onMapTap: () -> Unit, modifier: Modifier, effect: suspend (MapView) -> Unit) {
    val context = LocalContext.current
    val tapHandler by rememberUpdatedState(onMapTap)
    val tapOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                tapHandler()
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        })
    }
    val ref = remember { mutableStateOf<MapView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                isTilesScaledToDpi = true
                minZoomLevel = 1.6
                maxZoomLevel = 18.0
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(2.0)
                controller.setCenter(GeoPoint(15.0, 0.0))
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

private fun clearOverlays(mapView: MapView) {
    val keepOverlay = mapView.tag as? MapEventsOverlay
    mapView.overlays.clear()
    if (keepOverlay != null) mapView.overlays.add(keepOverlay)
}

private suspend fun drawWorldCountry(mapView: MapView, step: ShowStep.WorldCountry) {
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
        mapView.zoomToBoundingBox(bbox, false)
        val fitZoom = (mapView.zoomLevelDouble - 0.4).coerceIn(2.5, 10.0)
        mapView.controller.setZoom(2.0)
        mapView.controller.setCenter(GeoPoint(15.0, 0.0))
        val center = bbox.centerWithDateLine
        animateZoomDecel(mapView, GeoPoint(center.latitude, center.longitude), fitZoom, 1800L)
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

private suspend fun drawLocation(mapView: MapView, photo: com.travel.slideshow.model.PhotoItem) {
    val lat = photo.lat ?: return
    val lon = photo.lon ?: return
    clearOverlays(mapView)
    val geo = GeoPoint(lat, lon)
    mapView.overlays.add(
        Marker(mapView).apply {
            position = geo
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = (photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (photo.countryName ?: "")
        }
    )
    mapView.controller.setZoom(4.0)
    mapView.controller.setCenter(geo)
    animateZoomDecel(mapView, geo, 15.5, 1700L)
    mapView.invalidate()
}

private suspend fun animateZoomDecel(mapView: MapView, target: GeoPoint, targetZoom: Double, durationMs: Long) {
    val startZoom = mapView.zoomLevelDouble
    val startLat = mapView.mapCenter.latitude
    val startLon = mapView.mapCenter.longitude
    val frames = (durationMs / 16).coerceAtLeast(1)
    for (i in 1..frames) {
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
    totalSec: Float,
    elapsedMs: Long,
) {
    val from = step.from
    val to = step.photo
    val aLat = from.lat ?: return
    val aLon = from.lon ?: return
    val bLat = to.lat ?: return
    val bLon = to.lon ?: return
    clearOverlays(mapView)

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
    mapView.overlays.add(
        Marker(mapView).apply {
            position = GeoPoint(bLat, bLon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = (to.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (to.countryName ?: "")
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
    mapView.zoomToBoundingBox(bb, false, 120)
    mapView.invalidate()

    val totalMs = (totalSec * 1000).toLong().coerceAtLeast(2500)
    val elapsed = elapsedMs.coerceAtLeast(0)
    val budget = (totalMs - elapsed).coerceAtLeast(0)
    val zoomMs = run {
        val wanted = minOf(2200L, (totalMs * 0.35).toLong())
        if (budget > wanted + 800) wanted else (budget - 300).coerceIn(400L, wanted)
    }
    val remainMs = (totalMs - elapsed - zoomMs)
    if (full.size >= 2) {
        val drawMs = remainMs.coerceAtLeast(0)
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
            line.setPoints(partial)
            mapView.invalidate()
            kotlinx.coroutines.delay(if (drawMs <= 0) 0 else drawMs / steps)
        }
        line.setPoints(full)
        mapView.invalidate()
    }

    animateZoomDecel(mapView, GeoPoint(bLat, bLon), 15.5, zoomMs)
}