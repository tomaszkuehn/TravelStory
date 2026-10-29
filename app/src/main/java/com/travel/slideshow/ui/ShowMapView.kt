package com.travel.slideshow.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.travel.slideshow.data.geo.CountryInfo
import com.travel.slideshow.viewmodel.ShowStep
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun ShowMap(step: ShowStep.WorldCountry, modifier: Modifier = Modifier) {
    SingleMapView(step, modifier) { mapView -> drawWorldCountry(mapView, step) }
}

@Composable
fun ShowMap(step: ShowStep.LocationMap, modifier: Modifier = Modifier) {
    SingleMapView(step, modifier) { mapView -> drawLocation(mapView, step.photo) }
}

@Composable
private fun SingleMapView(key: Any, modifier: Modifier, effect: (MapView) -> Unit) {
    val context = LocalContext.current
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
            }.also { ref.value = it }
        }
    )
    val mv = ref.value
    DisposableEffect(Unit) { onDispose { ref.value = null } }
    LaunchedEffect(key, mv) {
        mv?.let { effect(it) }
    }
}

private fun drawWorldCountry(mapView: MapView, step: ShowStep.WorldCountry) {
    val country = step.country
    mapView.overlays.clear()
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
        mapView.controller.animateTo(
            GeoPoint(center.latitude, center.longitude),
            fitZoom,
            1600L,
        )
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

private fun drawLocation(mapView: MapView, photo: com.travel.slideshow.model.PhotoItem) {
    val lat = photo.lat ?: return
    val lon = photo.lon ?: return
    mapView.overlays.clear()
    val geo = GeoPoint(lat, lon)
    mapView.overlays.add(
        Marker(mapView).apply {
            position = geo
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = (photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (photo.countryName ?: "")
        }
    )
    mapView.controller.setZoom(6.0)
    mapView.controller.setCenter(geo)
    mapView.controller.animateTo(geo, 15.5, 1400L)
    mapView.invalidate()
}