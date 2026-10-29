package com.travel.slideshow.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.travel.slideshow.data.geo.CountryInfo
import com.travel.slideshow.model.PhotoItem
import com.travel.slideshow.viewmodel.ShowStep
import com.travel.slideshow.viewmodel.ShowViewModel
import com.travel.slideshow.viewmodel.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SlideshowScreen(vm: ShowViewModel) {
    val st by vm.state.collectAsState()
    val idleLike = st.step is ShowStep.Idle || st.step is ShowStep.Finished

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

        LaunchedEffect(st.step) {
            when (val s = st.step) {
                is ShowStep.WorldCountry, is ShowStep.LocationMap, is ShowStep.RouteMap -> {
                    mapLayerStep = s
                    showMapLayer = true
                    kotlinx.coroutines.coroutineScope {
                        launch { mapAlpha.animateTo(1f, fadeSpec) }
                        launch { photoAlpha.animateTo(0f, fadeSpec) }
                    }
                    showPhotoLayer = false
                }
                is ShowStep.Photo -> {
                    photoLayerPhoto = s.photo
                    showPhotoLayer = true
                    kotlinx.coroutines.coroutineScope {
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
                    PhotoView(photoUnder, vm) { vm.togglePause() }
                }
            }
            val mapUnder = mapLayerStep
            if (mapUnder != null && showMapLayer) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = mapAlpha.value }) {
                    when (val s = mapUnder) {
                        is ShowStep.WorldCountry -> ShowMap(s, st.visitedPins, { vm.togglePause() }, vm::onMapGesture, { vm.manualHold.value }, Modifier.fillMaxSize())
                        is ShowStep.LocationMap -> ShowMap(
                            s,
                            st.pastRoutes,
                            st.visitedPins,
                            { vm.togglePause() },
                            vm::onMapGesture,
                            { vm.manualHold.value },
                            { vm.markVisited(s.photo.lat ?: 0.0, s.photo.lon ?: 0.0, (s.photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (s.photo.countryName ?: "")) },
                            Modifier.fillMaxSize(),
                        )
                        is ShowStep.RouteMap ->
                            ShowMap(
                                s,
                                st.route?.takeIf { it.uri == s.photo.uri }?.points,
                                st.pastRoutes,
                                st.visitedPins,
                                st.config.locationMapSec,
                                st.runId,
                                { vm.togglePause() },
                                vm::onMapGesture,
                                { vm.manualHold.value },
                                { vm.markVisited(s.photo.lat ?: 0.0, s.photo.lon ?: 0.0, (s.photo.iso3?.let { CountryInfo.flagEmoji(it) + " " }) + (s.photo.countryName ?: "")) },
                                Modifier.fillMaxSize()
                            )
                        else -> {}
                    }
                }
            }
            when (val s = st.step) {
                is ShowStep.Idle -> IdleView(st, vm)
                is ShowStep.Scanning -> ScanningView(st, s, vm)
                is ShowStep.Finished -> FinishedView(vm)
                else -> {}
            }
        }

        InfoChip(st, Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 2.dp))

        if (!idleLike) {
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
            visible = st.paused || idleLike,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )
    }
}

@Composable
private fun InfoChip(st: UiState, modifier: Modifier) {
    val (flag, _) = when (val step = st.step) {
        is ShowStep.WorldCountry -> CountryInfo.flagEmoji(step.country.iso3) to step.country.name
        is ShowStep.LocationMap -> stFlag(step.photo)
        is ShowStep.Photo -> stFlag(step.photo)
        else -> null
    } ?: return
    Text(
        flag,
        fontSize = 30.sp,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 10.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

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
                "${it} zdjęć (z lokalizacją: ${st.withLocation})",
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
private fun PhotoView(photo: PhotoItem, vm: ShowViewModel, onPhotoTap: () -> Unit) {
    val threshold = with(LocalDensity.current) { 140.dp.toPx() }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    AnimatedContent(
        targetState = photo,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
        label = "photo"
    ) { p ->
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(p.uri)
                .crossfade(true)
                .build(),
            contentDescription = p.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onPhotoTap() },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum.floatValue = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragAccum.floatValue += amount
                        },
                        onDragEnd = {
                            if (dragAccum.floatValue <= -threshold) vm.skipNext()
                            else if (dragAccum.floatValue >= threshold) vm.skipPrevious()
                        }
                    )
                }
        )
    }
}

@Composable
private fun FinishedView(vm: ShowViewModel) {
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