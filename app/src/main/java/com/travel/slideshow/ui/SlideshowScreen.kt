package com.travel.slideshow.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.IntOffset
import com.travel.slideshow.data.FaceFocus
import com.travel.slideshow.data.geo.Geo
import com.travel.slideshow.model.KenBurnsMode
import com.travel.slideshow.model.TransitionStyle
import com.travel.slideshow.model.TravelMode
import com.travel.slideshow.viewmodel.FinaleStats
import com.travel.slideshow.viewmodel.lengthKm
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.geometry.isSpecified
import android.os.Build
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChanged
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.travel.slideshow.data.geo.CountryInfo
import com.travel.slideshow.model.PhotoItem
import com.travel.slideshow.viewmodel.DayBadge
import com.travel.slideshow.viewmodel.ShowStep
import com.travel.slideshow.viewmodel.ShowViewModel
import com.travel.slideshow.viewmodel.UiState
import com.travel.slideshow.viewmodel.placeOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import kotlinx.coroutines.delay
import com.travel.slideshow.media.ExportState
import com.travel.slideshow.media.VideoExport
import kotlinx.coroutines.launch

@Composable
fun SlideshowScreen(vm: ShowViewModel) {
    val st by vm.state.collectAsState()
    val awaiting = st.awaitingStart && st.step is ShowStep.Intro
    val idleLike = st.step is ShowStep.Idle || st.step is ShowStep.Finished || awaiting
    val exportState by VideoExport.state.collectAsState()
    val recording = exportState is ExportState.Recording
    val startExport = rememberVideoExport(vm.showTitle())
    val exportIdle = exportState is ExportState.Idle
    val onRecord = startExport?.takeIf { exportIdle }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { vm.togglePause() },
                )
            }
    ) {
        val fadeSpec = tween<Float>((st.config.transitionSec * 1000).toInt().coerceIn(150, 8000))
        var mapLayerStep by remember { mutableStateOf<ShowStep?>(null) }
        var showMapLayer by remember { mutableStateOf(false) }
        var photoLayerPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        var showPhotoLayer by remember { mutableStateOf(false) }
        val mapAlpha = remember { Animatable(0f) }
        val photoAlpha = remember { Animatable(0f) }
        var introCard by remember { mutableStateOf<ShowStep.Intro?>(null) }
        val introAlpha = remember { Animatable(0f) }
        var lastPhoto by remember { mutableStateOf<PhotoItem?>(null) }
        val flash = remember { Animatable(0f) }
        var routeProgress by remember { mutableStateOf<RouteProgress?>(null) }

        LaunchedEffect(st.step) {
            val s = st.step
            if (s is ShowStep.Intro) {
                introCard = s
                introAlpha.animateTo(1f, fadeSpec)
            } else if (introCard != null) {
                introAlpha.animateTo(0f, fadeSpec)
                introCard = null
            }
        }


        LaunchedEffect(st.step) {
            val s = st.step
            // The closing summary sits over the map of the whole trip.
            val onMap = s is ShowStep.WorldCountry || s is ShowStep.LocationMap || s is ShowStep.RouteMap ||
                (s is ShowStep.Finished && st.finale != null)
            when {
                onMap -> {
                    mapLayerStep = s
                    showMapLayer = true
                    kotlinx.coroutines.coroutineScope {
                        launch { mapAlpha.animateTo(1f, fadeSpec) }
                        launch { photoAlpha.animateTo(0f, fadeSpec) }
                    }
                    showPhotoLayer = false
                }
                s is ShowStep.Photo -> {
                    photoLayerPhoto = s.photo
                    showPhotoLayer = true
                    val prev = lastPhoto
                    lastPhoto = s.photo
                    val newDay = st.config.transitionStyle != TransitionStyle.FADE && prev != null &&
                        localDay(prev) != null && localDay(s.photo) != null && localDay(prev) != localDay(s.photo)
                    kotlinx.coroutines.coroutineScope {
                        if (newDay) launch {
                            flash.snapTo(0.85f)
                            flash.animateTo(0f, tween(1300))
                        }
                        launch { photoAlpha.animateTo(1f, fadeSpec) }
                        launch { mapAlpha.animateTo(0f, fadeSpec) }
                    }
                    showMapLayer = false
                }
                else -> kotlinx.coroutines.coroutineScope {
                    launch { mapAlpha.animateTo(0f, fadeSpec) }
                    launch { photoAlpha.animateTo(0f, fadeSpec) }
                    showMapLayer = false
                    showPhotoLayer = false
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            val photoUnder = photoLayerPhoto
            if (photoUnder != null && showPhotoLayer) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = photoAlpha.value }) {
                    PhotoView(photoUnder, st.paused, (st.config.photoDisplaySec * 1000).toLong(), st.config.transitionStyle,
                        st.config.kenBurns, st.config.faceFocus, vm) { vm.togglePause() }
                }
            }
            val mapUnder = mapLayerStep
            if (mapUnder != null && showMapLayer) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = mapAlpha.value }) {
                    val rm = mapUnder as? ShowStep.RouteMap
                    val route = st.route?.takeIf { rm != null && it.uri == rm.photo.uri }
                    ShowMap(
                        mapUnder,
                        route,
                        st.pastRoutes,
                        st.visitedPins,
                        st.pinNumbers,
                        st.config.travelMode,
                        st.config.locationMapSec,
                        st.runId,
                        { vm.togglePause() },
                        vm::onUserGesture,
                        { vm.manualHold.value },
                        vm::onArrived,
                        st.config.mapStyle,
                        { routeProgress = it },
                        Modifier.fillMaxSize(),
                    )
                }
            }
            introCard?.let { card ->
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = introAlpha.value }) {
                    IntroView(card, st.config.introTitle.ifBlank { st.photoFolderName ?: "Moja podróż" })
                }
            }
            // Brief white flash when the photos move on to a new day.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = flash.value }.background(Color.White))
            when (val s = st.step) {
                is ShowStep.Idle -> IdleView(st, vm)
                is ShowStep.Scanning -> ScanningView(st, s, vm)
                is ShowStep.Finished -> st.finale?.let { FinaleView(it, vm, recording, onRecord) } ?: FinishedView(vm, onRecord)
                is ShowStep.Intro -> if (awaiting) StartBar(st, vm, onRecord)
                else -> {}
            }
        }

        InfoChip(st, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 2.dp))

        val leg = st.step as? ShowStep.RouteMap
        val prog = routeProgress?.takeIf { st.config.kmCounter && leg != null && it.uri == leg.photo.uri }
        if (prog != null) {
            val before = remember(st.pastRoutes, prog.uri) {
                st.pastRoutes.filter { it.uri != prog.uri }.sumOf { it.lengthKm() }
            }
            KmCounter(
                prog, before, st.config.travelMode,
                Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, bottom = if (st.paused) 76.dp else 14.dp)
            )
        }

        if (!idleLike && !recording) {
            IconButton(
                onClick = { vm.setSettingsVisible(true) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 6.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Ustawienia",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        ControlsOverlay(
            vm = vm,
            st = st,
            visible = st.paused || (idleLike && !awaiting && st.finale == null),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )
        VideoExportStatus()
    }
}

/** "Nagraj wideo" button: plays the whole show from the start and saves it as an MP4. */
@Composable
private fun RecordButton(onRecord: () -> Unit) {
    OutlinedButton(
        onClick = onRecord,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
    ) { Text("⏺ Nagraj wideo") }
}

@Composable
private fun InfoChip(st: UiState, modifier: Modifier) {
    val photo = when (val step = st.step) {
        is ShowStep.LocationMap -> step.photo
        is ShowStep.RouteMap -> step.photo
        is ShowStep.Photo -> step.photo
        else -> null
    }
    val flag = when (val step = st.step) {
        is ShowStep.WorldCountry -> CountryInfo.flagEmoji(step.country.iso3)
        else -> photo?.let { stFlag(it)?.first }
    }
    val place = if (st.config.placeNames) placeOf(photo, st.placeNames) else null
    val onShowStep = st.step is ShowStep.WorldCountry || st.step is ShowStep.LocationMap ||
        st.step is ShowStep.RouteMap || st.step is ShowStep.Photo
    val badge = st.dayBadge?.takeIf { onShowStep }
    // Remembered so the note can fade out after the segment that carried it.
    var shownBadge by remember { mutableStateOf<DayBadge?>(null) }
    if (badge != null) shownBadge = badge
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 10.dp)
    ) {
        if (flag != null || place != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 2.dp)
            ) {
                flag?.let { Text(it, fontSize = 20.sp) }
                place?.let { Text(it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
        AnimatedVisibility(visible = badge != null, enter = fadeIn(tween(600)), exit = fadeOut(tween(600))) {
            shownBadge?.let { DayNote(it) }
        }
    }
}

/** Compact "Dzień 3  14.07.2025" under the flag on the first map or photo of a new day. */
@Composable
private fun DayNote(badge: DayBadge) {
    val date = remember(badge.localDayMs) {
        // localDayMs is wall-clock midnight encoded as UTC, so format it in UTC.
        SimpleDateFormat("dd.MM.yyyy", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(badge.localDayMs))
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 1.dp)
    ) {
        Text("Dzień ${badge.dayNumber}", color = Color(0xFFFFB74D), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(date, color = Color.White, fontSize = 12.sp)
    }
}

/** Opening card: title, dates and flags over a slowly zooming, faded collage of show photos. */
@Composable
private fun IntroView(card: ShowStep.Intro, title: String) {
    val dates = remember(card.fromMs, card.toMs) { dateRange(card.fromMs, card.toMs) }
    val zoom = remember(card) { Animatable(1f) }
    LaunchedEffect(card) { zoom.animateTo(1.08f, tween(9000, easing = LinearEasing)) }
    Box(Modifier.fillMaxSize().background(Color.Black))
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }
    ) {
        val n = card.photos.size
        val landscape = maxWidth > maxHeight
        val (cols, rows) = when {
            n <= 1 -> 1 to 1
            n == 2 -> if (landscape) 2 to 1 else 1 to 2
            n == 3 -> if (landscape) 3 to 1 else 1 to 3
            n == 4 -> 2 to 2
            else -> if (landscape) 3 to 2 else 2 to 3
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (c in 0 until cols) {
                        val p = card.photos.getOrNull(r * cols + c)
                        Box(Modifier.weight(1f).fillMaxSize().clipToBounds()) {
                            if (p != null) {
                                Image(
                                    rememberAsyncImagePainter(
                                        ImageRequest.Builder(LocalContext.current).data(p.uri).crossfade(true).build()
                                    ),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = INTRO_PHOTO_ALPHA },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // Darker middle keeps the title readable over bright photos.
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 50.sp,
            lineHeight = 56.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        dates?.let {
            Text(it, color = Color(0xFFFFB74D), fontSize = 22.sp, modifier = Modifier.padding(top = 12.dp))
        }
        if (card.countries.isNotEmpty()) {
            Text(
                card.countries.take(12).joinToString("  ") { CountryInfo.flagEmoji(it) },
                fontSize = 26.sp,
                modifier = Modifier.padding(top = 14.dp)
            )
        }
    }
}

/** Start button under the opening card while it waits for the show to be started. */
@Composable
private fun StartBar(st: UiState, vm: ShowViewModel, onRecord: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 28.dp)
    ) {
        st.message?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.start() }) { Text("▶ Start", fontSize = 18.sp) }
            onRecord?.let { RecordButton(it) }
            IconButton(
                onClick = { vm.setSettingsVisible(true) },
                modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
            }
        }
    }
}

/** "12–19 lipca 2025", "28 czerwca – 3 lipca 2025" or "30 grudnia 2024 – 2 stycznia 2025"; times are wall clock as UTC. */
private fun dateRange(fromMs: Long?, toMs: Long?): String? {
    if (fromMs == null || toMs == null) return null
    val pl = Locale("pl")
    val utc = TimeZone.getTimeZone("UTC")
    fun fmt(pattern: String, ms: Long) =
        SimpleDateFormat(pattern, pl).apply { timeZone = utc }.format(Date(ms))
    val y1 = fmt("yyyy", fromMs); val y2 = fmt("yyyy", toMs)
    val m1 = fmt("MM", fromMs); val m2 = fmt("MM", toMs)
    val d1 = fmt("d", fromMs); val d2 = fmt("d", toMs)
    return when {
        y1 != y2 -> fmt("d MMMM yyyy", fromMs) + " – " + fmt("d MMMM yyyy", toMs)
        m1 != m2 -> fmt("d MMMM", fromMs) + " – " + fmt("d MMMM yyyy", toMs)
        d1 != d2 -> "$d1–" + fmt("d MMMM yyyy", toMs)
        else -> fmt("d MMMM yyyy", fromMs)
    }
}

private const val INTRO_PHOTO_ALPHA = 0.38f

private fun stFlag(photo: PhotoItem): Pair<String, String>? {
    val iso = photo.iso3 ?: return null
    return CountryInfo.flagEmoji(iso) to (photo.countryName ?: "")
}

@Composable
private fun IdleView(st: UiState, vm: ShowViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("🌍", fontSize = 64.sp)
        Text("Podróżnicze Slideshow", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        SpacerHeight(8.dp)
        Text(
            text = st.photoFolderName?.let { "Folder: $it" } ?: "Nie wybrano folderu ze zdjęciami",
            color = Color(0xFFBDBDBD),
            fontSize = 14.sp
        )
        st.itemCount.takeIf { it > 0 }?.let {
            Text(
                "${it} plików (z lokalizacją: ${st.withLocation}" +
                    (if (st.videoCount > 0) ", filmy: ${st.videoCount})" else ")"),
                color = Color(0xFF8BC34A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        st.message?.let {
            Text(it, color = Color(0xFFFF5252), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        SpacerHeight(20.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (st.config.photoFolderUri == null) {
                Button(onClick = { vm.setSettingsVisible(true) }) {
                    Text("Wybierz folder ze zdjęciami")
                }
            } else if (st.itemCount == 0) {
                Button(onClick = { vm.prepare() }) { Text("Wczytaj zdjęcia") }
                IconButton(onClick = { vm.setSettingsVisible(true) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
                }
            } else {
                Button(onClick = { vm.start() }) { Text("▶ Start") }
                IconButton(onClick = { vm.setSettingsVisible(true) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ScanningView(st: UiState, step: ShowStep.Scanning, vm: ShowViewModel) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        CircularProgressIndicator(color = Color.White)
        SpacerHeight(16.dp)
        Text("Skanowanie zdjęć… ${step.count}", color = Color.White, fontSize = 18.sp)
        Text(
            "Czytam dane EXIF (lokalizację) z plików",
            color = Color(0xFFBDBDBD), fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        SpacerHeight(24.dp)
        Button(onClick = { vm.stopScanning() }) {
            Text("⏹ Zatrzymaj skanowanie")
        }
        Text(
            "Pokaz użyje zdjęć przeskanowanych do tej pory",
            color = Color(0xFFBDBDBD), fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun PhotoView(
    photo: PhotoItem,
    paused: Boolean,
    displayMs: Long,
    style: TransitionStyle,
    kenBurns: KenBurnsMode,
    faceFocus: Boolean,
    vm: ShowViewModel,
    onPhotoTap: () -> Unit,
) {
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    val hold by vm.manualHold.collectAsState()
    AnimatedContent(
        targetState = photo,
        transitionSpec = { photoTransition(initialState, targetState, style) },
        label = "photo"
    ) { p ->
        // Pinch zoom / pan of this photo; a new photo starts unzoomed.
        var scale by remember(p.uri) { mutableFloatStateOf(1f) }
        var offset by remember(p.uri) { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            ) {
                // While the user explores the photo the show holds, so the clip / Ken Burns wait too.
                if (p.isVideo) {
                    VideoView(p, paused || hold)
                } else {
                    StillPhoto(p, paused || hold, displayMs, kenBurns, faceFocus)
                }
            }
            // Gestures sit on top so the video surface cannot swallow them.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(p.uri) {
                        detectTapGestures(
                            onTap = { onPhotoTap() },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                                vm.onUserGesture()
                            },
                        )
                    }
                    .pointerInput(p.uri) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var drag = 0f
                            var transformed = false
                            do {
                                val event = awaitPointerEvent()
                                val fingers = event.changes.count { it.pressed }
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (fingers >= 2 || scale > 1f) {
                                    if (zoom != 1f || pan != Offset.Zero) {
                                        transformed = true
                                        scale = (scale * zoom).coerceIn(1f, MAX_PHOTO_ZOOM)
                                        val maxX = (scale - 1f) * size.width / 2f
                                        val maxY = (scale - 1f) * size.height / 2f
                                        offset = Offset(
                                            (offset.x + pan.x).coerceIn(-maxX, maxX),
                                            (offset.y + pan.y).coerceIn(-maxY, maxY),
                                        )
                                        vm.onUserGesture()
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } else {
                                    drag += pan.x
                                    if (kotlin.math.abs(drag) > viewConfiguration.touchSlop) {
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            // A one-finger swipe on an unzoomed photo still skips.
                            if (!transformed && scale <= 1f) {
                                if (drag <= -threshold) vm.skipNext()
                                else if (drag >= threshold) vm.skipPrevious()
                            }
                        }
                    }
            )
        }
    }
}

private const val MAX_PHOTO_ZOOM = 5f

private const val DAY_MS = 86_400_000L

private fun localDay(p: PhotoItem): Long? = p.localTimeMs?.let { Math.floorDiv(it, DAY_MS) }

/**
 * Photo-to-photo change. Slides move in the direction of travel between the two shots (from the right
 * when there is none); the mixed style varies fade, slide and zoom from photo to photo.
 */
private fun AnimatedContentTransitionScope<PhotoItem>.photoTransition(
    from: PhotoItem,
    to: PhotoItem,
    style: TransitionStyle,
): ContentTransform {
    val kind = when (style) {
        TransitionStyle.FADE -> 0
        TransitionStyle.SLIDE -> 1
        TransitionStyle.MIXED -> Math.floorMod(to.uri.hashCode(), 4)
    }
    return when (kind) {
        1, 3 -> {
            val (dx, dy) = travelDirection(from, to)
            val move = tween<IntOffset>(950, easing = FastOutSlowInEasing)
            (slideIn(move) { sz -> IntOffset(dx * sz.width, -dy * sz.height) } + fadeIn(tween(450))) togetherWith
                (slideOut(move) { sz -> IntOffset(-dx * sz.width, dy * sz.height) } + fadeOut(tween(800)))
        }
        2 -> (scaleIn(tween(1100, easing = FastOutSlowInEasing), initialScale = 1.18f) + fadeIn(tween(700))) togetherWith
            (scaleOut(tween(800), targetScale = 0.94f) + fadeOut(tween(600)))
        else -> fadeIn(tween(700)) togetherWith fadeOut(tween(500))
    }
}

/** Main compass direction from one shot to the next: (1, 0) east, (0, 1) north; east when unknown. */
private fun travelDirection(from: PhotoItem, to: PhotoItem): Pair<Int, Int> {
    val aLat = from.lat ?: return 1 to 0
    val aLon = from.lon ?: return 1 to 0
    val bLat = to.lat ?: return 1 to 0
    val bLon = to.lon ?: return 1 to 0
    if (Geo.haversineKm(aLat, aLon, bLat, bLon) < 0.03) return 1 to 0
    val east = (bLon - aLon) * Math.cos(Math.toRadians((aLat + bLat) / 2))
    val north = bLat - aLat
    return if (kotlin.math.abs(east) >= kotlin.math.abs(north)) (if (east >= 0) 1 else -1) to 0
    else 0 to (if (north >= 0) 1 else -1)
}

/**
 * A photo too narrow for the screen (portrait on a landscape screen, or one that would fill at most
 * half the width) sits over a blurred, dimmed copy of itself. Ken Burns slowly zooms the narrow ones
 * (drifting down) or every photo, aiming at the faces in it when they were found.
 */
@Composable
private fun StillPhoto(photo: PhotoItem, paused: Boolean, displayMs: Long, kenBurns: KenBurnsMode, faceFocus: Boolean) {
    val context = LocalContext.current
    val useFaces = faceFocus && kenBurns != KenBurnsMode.OFF
    LaunchedEffect(photo.uri, useFaces) { if (useFaces) FaceFocus.prefetch(context, photo.uri) }
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(LocalContext.current)
            .data(photo.uri)
            .crossfade(true)
            .build()
    )
    val imgSize = (painter.state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val known = imgSize != null && imgSize.isSpecified && imgSize.width > 0f && imgSize.height > 0f
        val portraitOnLandscape = known && imgSize!!.height > imgSize.width && maxWidth > maxHeight
        // Any photo that fitted to the screen would take at most half its width, on either screen orientation.
        val narrow = known && maxHeight * (imgSize!!.width / imgSize.height) <= maxWidth / 2
        val narrowish = portraitOnLandscape || narrow
        val animate = known && when (kenBurns) {
            KenBurnsMode.OFF -> false
            KenBurnsMode.NARROW -> narrowish
            KenBurnsMode.ALL -> true
        }
        val progress = remember(photo.uri) { Animatable(0f) }
        LaunchedEffect(photo.uri, paused, animate) {
            if (paused || !animate) return@LaunchedEffect
            val left = 1f - progress.value
            progress.animateTo(1f, tween((displayMs * left).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
        // Read once the image is loaded, which gives the face search a head start.
        val face = remember(photo.uri, known, useFaces) { if (useFaces && known) FaceFocus.faceAt(photo.uri) else null }
        val variant = Math.floorMod(photo.uri.hashCode(), KB_FOCI.size)
        // Without a face: narrow photos drift down, others aim at varied spots; some of those zoom out instead.
        val focus = face ?: if (narrowish) FaceFocus.Point(0.5f, 1f) else KB_FOCI[variant]
        val zoomOut = face == null && !narrowish && variant % 2 == 1
        val boxW = with(LocalDensity.current) { maxWidth.toPx() }
        val boxH = with(LocalDensity.current) { maxHeight.toPx() }
        if (narrowish) {
            Image(
                painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // Blur needs Android 12; older devices get the dimmed copy only.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(36.dp) else Modifier
                    )
                    .graphicsLayer { alpha = 0.45f }
            )
        }
        Image(
            painter,
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!animate) return@graphicsLayer
                    val t = progress.value
                    val scale = 1f + KB_ZOOM * (if (zoomOut) 1f - t else t)
                    scaleX = scale
                    scaleY = scale
                    // Keeping the focus point in place while scaling about the centre zooms towards it;
                    // the shift never exceeds the room the zoom adds, so no edge comes into view.
                    val ar = imgSize!!.width / imgSize.height
                    val drawnW = minOf(boxW, boxH * ar)
                    val drawnH = drawnW / ar
                    translationX = -(focus.x - 0.5f) * drawnW * (scale - 1f)
                    translationY = -(focus.y - 0.5f) * drawnH * (scale - 1f)
                }
        )
    }
}

/** Ken Burns zoom reached at the end of the display time. */
private const val KB_ZOOM = 0.12f

/** Zoom targets for photos without a detected face (fractions of width and height). */
private val KB_FOCI = listOf(
    FaceFocus.Point(0.5f, 0.45f),
    FaceFocus.Point(0.35f, 0.4f),
    FaceFocus.Point(0.65f, 0.4f),
    FaceFocus.Point(0.5f, 0.35f),
)

/** "🚗 +124 km · łącznie 1 380 km" while a leg is drawn. */
@Composable
private fun KmCounter(p: RouteProgress, beforeKm: Double, mode: TravelMode, modifier: Modifier) {
    val icon = when {
        p.flight -> "✈️"
        mode == TravelMode.BIKE -> "\uD83D\uDEB2"
        mode == TravelMode.FOOT -> "\uD83D\uDEB6"
        else -> "\uD83D\uDE97"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(icon, fontSize = 18.sp)
        Text("+" + formatKm(p.doneKm) + " km", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (beforeKm >= 0.05) {
            Text(
                "· łącznie " + formatKm(beforeKm + p.doneKm) + " km",
                color = Color(0xFFBDBDBD),
                fontSize = 14.sp,
            )
        }
    }
}

/** "3,4" below 10 km, "1 380" above. */
private fun formatKm(km: Double): String =
    if (km < 10) String.format(Locale("pl"), "%.1f", km)
    else String.format(Locale("pl"), "%,d", km.toLong())

@OptIn(UnstableApi::class)
@Composable
private fun VideoView(photo: PhotoItem, paused: Boolean) {
    val context = LocalContext.current
    val player = remember(photo.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(photo.uri))
            prepare()
        }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Polish plural form for [n]: 1 zdjęcie, 2 zdjęcia, 5 zdjęć. */
private fun plural(n: Int, one: String, few: String, many: String): String = when {
    n == 1 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

/**
 * Closing summary over the map of the whole trip: title, dates, statistics counting up from zero and
 * the flags of the countries visited appearing one by one, while the music fades away.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinaleView(f: FinaleStats, vm: ShowViewModel, recording: Boolean, onRecord: (() -> Unit)?) {
    val progress = remember(f) { Animatable(0f) }
    val shown = remember(f) { Animatable(0f) }
    LaunchedEffect(f) {
        shown.animateTo(1f, tween(900))
        delay(600)
        progress.animateTo(1f, tween(FINALE_COUNT_MS, easing = FastOutSlowInEasing))
    }
    val dates = remember(f) { dateRange(f.fromMs, f.toMs) }
    val t = progress.value
    fun count(n: Int) = Math.round(n * t).toInt()
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value }) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
        ) {
            Text(
                f.title,
                color = Color.White,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            dates?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                if (f.km >= 0.5) StatTile(formatKm(f.km * t), "km")
                if (f.days > 0) StatTile(count(f.days).toString(), plural(f.days, "dzień", "dni", "dni"))
                if (f.countries.isNotEmpty()) {
                    val n = f.countries.size
                    StatTile(count(n).toString(), plural(n, "kraj", "kraje", "krajów"))
                }
                if (f.places > 0) StatTile(count(f.places).toString(), plural(f.places, "miejsce", "miejsca", "miejsc"))
                if (f.photos > 0) StatTile(count(f.photos).toString(), plural(f.photos, "zdjęcie", "zdjęcia", "zdjęć"))
                if (f.videos > 0) StatTile(count(f.videos).toString(), plural(f.videos, "film", "filmy", "filmów"))
            }
            if (f.countries.isNotEmpty()) {
                val flags = f.countries.take(16)
                val visible = Math.ceil(flags.size * t.toDouble()).toInt()
                Text(
                    flags.take(visible).joinToString("  ") { CountryInfo.flagEmoji(it) },
                    fontSize = 26.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            SpacerHeight(14.dp)
            // Kept out of an exported film; the space stays so nothing shifts when they appear.
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.graphicsLayer { alpha = if (recording) 0f else 1f }
            ) {
                Button(onClick = { vm.restartAll() }, enabled = !recording) { Text("Jeszcze raz") }
                Button(onClick = { vm.setSettingsVisible(true) }, enabled = !recording) { Text("Ustawienia") }
                if (!recording) onRecord?.let { RecordButton(it) }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color(0xFFBDBDBD), fontSize = 13.sp)
    }
}

private const val FINALE_COUNT_MS = 3200

@Composable
private fun FinishedView(vm: ShowViewModel, onRecord: (() -> Unit)?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text("Koniec pokazu", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SpacerHeight(16.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.restartAll() }) { Text("Jeszcze raz") }
            Button(onClick = { vm.setSettingsVisible(true) }) { Text("Ustawienia") }
            onRecord?.let { RecordButton(it) }
        }
    }
}

@Composable
fun ControlsOverlay(vm: ShowViewModel, st: UiState, visible: Boolean, modifier: Modifier = Modifier) {
    if (!visible) return
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        shape = CircleShape,
        modifier = modifier.fillMaxWidth(0.94f)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (st.paused) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color(0xFFFFB74D),
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = { vm.togglePause() }) {
                Icon(
                    if (st.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = "Pauza/Odtwarzaj",
                    tint = Color.White
                )
            }
            IconButton(onClick = { vm.skipNext() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Następne", tint = Color.White)
            }
            IconButton(onClick = { vm.skipPrevious() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Poprzednie", tint = Color.White)
            }
            IconButton(onClick = { vm.skipPreviousDay() }) {
                Icon(Icons.Filled.FastRewind, contentDescription = "Poprzedni dzień", tint = Color(0xFF81D4FA))
            }
            IconButton(onClick = { vm.skipNextDay() }) {
                Icon(Icons.Filled.FastForward, contentDescription = "Następny dzień", tint = Color(0xFF81D4FA))
            }
            IconButton(onClick = { vm.restartCountry() }) {
                Icon(Icons.Filled.Replay, contentDescription = "Od początku bieżącego państwa", tint = Color(0xFFFFB74D))
            }
            IconButton(onClick = { vm.restartAll() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Od początku pokazu", tint = Color.White)
            }
            IconButton(onClick = { vm.setSettingsVisible(true) }) {
                Icon(Icons.Filled.Settings, contentDescription = "Ustawienia", tint = Color.White)
            }
        }
    }
}

@Composable
fun SpacerHeight(h: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(width = 0.dp, height = h))
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}