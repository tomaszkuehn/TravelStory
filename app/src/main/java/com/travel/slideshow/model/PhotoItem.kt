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
)
