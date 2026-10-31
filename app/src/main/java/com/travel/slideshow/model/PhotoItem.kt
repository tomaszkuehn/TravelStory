package com.travel.slideshow.model

import android.net.Uri

data class PhotoItem(
    val uri: Uri,
    val name: String,
    /** Real instant (UTC) used for ordering. */
    val dateTakenMs: Long?,
    val lat: Double?,
    val lon: Double?,
    /** Wall-clock time where the shot was taken, encoded as if it were UTC; used for calendar days. */
    val localTimeMs: Long? = dateTakenMs,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    /** Video only: whether the clip has a sound track; null when unknown (treated as having one). */
    val hasAudio: Boolean? = null,
    var iso3: String? = null,
    var countryName: String? = null,
)

data class ShowConfig(
    val photoFolderUri: Uri? = null,
    val musicFolderUri: Uri? = null,
    val photoDisplaySec: Float = 5f,
    val locationMapSec: Float = 6f,
    val worldMapSec: Float = 9f,
    val distanceKmThreshold: Double = 5.0,
    val musicEnabled: Boolean = true,
    val musicVolumePct: Int = 80,
    val newestFirst: Boolean = false,
    val onlyWithLocation: Boolean = false,
    val transitionSec: Float = 1.0f,
    val showVideos: Boolean = true,
    val maxVideoSec: Float = 20f,
    val dayTitles: Boolean = true,
    val placeNames: Boolean = true,
    val travelMode: TravelMode = TravelMode.CAR,
    /** Opening card: title over a faded collage of photos from the show. */
    val introEnabled: Boolean = true,
    /** Opening card title; blank means the photo folder's name. */
    val introTitle: String = "",
    /** Closing screen: the whole trail on the map with counted-up trip statistics. */
    val finaleEnabled: Boolean = true,
    /** Distance counter in the corner while a route is drawn. */
    val kmCounter: Boolean = true,
    /** Photo changes wait for the nearest beat of the music (tracks with a detectable tempo). */
    val beatSync: Boolean = false,
    val transitionStyle: TransitionStyle = TransitionStyle.FADE,
    val kenBurns: KenBurnsMode = KenBurnsMode.NARROW,
    /** Ken Burns zooms towards detected faces. */
    val faceFocus: Boolean = true,
    val mapStyle: MapStyle = MapStyle.STANDARD,
)

/** How legs between photos are routed on roads (long hops are flights either way). */
enum class TravelMode(val label: String) { CAR("Samochód"), BIKE("Rower"), FOOT("Pieszo") }

/** How one photo gives way to the next. */
enum class TransitionStyle(val label: String) {
    FADE("Przenikanie"),
    /** Slides in the direction of travel when the place changes, flashes on a new day. */
    SLIDE("Przesunięcie"),
    /** Picks a different effect each time: fade, slide, zoom, flash on a new day. */
    MIXED("Mieszane"),
}

/** Slow pan and zoom over still photos. */
enum class KenBurnsMode(val label: String) { OFF("Wyłączony"), NARROW("Wąskie zdjęcia"), ALL("Wszystkie") }

enum class MapStyle(val label: String) {
    STANDARD("Standardowa"), TOPO("Teren"), LIGHT("Jasna"), DARK("Ciemna"), SATELLITE("Satelita")
}
