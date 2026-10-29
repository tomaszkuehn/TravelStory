package com.travel.slideshow.ui

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
        when (val step = st.step) {
            is ShowStep.Idle -> IdleView(st, vm)
            is ShowStep.Scanning -> ScanningView(st, step, vm)
            is ShowStep.WorldCountry -> ShowMap(step, Modifier.fillMaxSize())
            is ShowStep.LocationMap -> ShowMap(step, Modifier.fillMaxSize())
            is ShowStep.Photo -> PhotoView(step.photo, vm)
            is ShowStep.Finished -> FinishedView(vm)
        }

        InfoChip(st, Modifier.align(Alignment.TopCenter))

        if (st.paused && !idleLike) {
            Text(
                "⏸ PAUZA",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
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
private fun PhotoView(photo: PhotoItem, vm: ShowViewModel) {
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
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum.floatValue = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragAccum.floatValue += amount
                        },
                        onDragEnd = {
                            if (dragAccum.floatValue <= -threshold) vm.skipNext()
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