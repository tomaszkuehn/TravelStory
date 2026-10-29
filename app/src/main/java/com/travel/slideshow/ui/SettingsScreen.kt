package com.travel.slideshow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.travel.slideshow.viewmodel.ShowViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ShowViewModel) {
    val st by vm.state.collectAsState()
    val cfg = st.config

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onPhotoFolderPicked(uri)
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onMusicFolderPicked(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia pokazu") },
                navigationIcon = {
                    IconButton(onClick = { vm.setSettingsVisible(false) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Wstecz")
                    }
                }
            )
        }
    ) { pad ->
        Surface(Modifier.padding(pad)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingRow(
                    title = "Folder ze zdjęciami",
                    value = st.photoFolderName ?: "nie wybrano"
                ) {
                    Button(onClick = { photoPicker.launch(null) }) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(" Wybierz")
                    }
                }
                Text(
                    "Zdjęcia są skanowane z podfolderów. Slajdy z GPS pokazują mapę; mapa świata + zblizenie przy zmianie państwa.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SettingRow(
                    title = "Folder z muzyką (wg państwa)",
                    value = st.musicFolderName ?: "nie wybrano",
                ) {
                    OutlinedButton(onClick = { musicPicker.launch(null) }) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Text(" Wybierz")
                    }
                }
                Text(
                    "Podfoldery: kod państwa (np. \"PL\", \"POL\"), nazwa (np. \"Poland\"), lub \"_default\" (musyka zastepcza). " +
                        "Brak plików — aplikacja zagra wygenerowany podkład.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tylko zdjęcia z lokalizacją")
                        Text("pomija zdjęcia bez danych GPS", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cfg.onlyWithLocation, onCheckedChange = { on ->
                        vm.updateConfig { it.copy(onlyWithLocation = on) }
                    })
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Muzyka podczas zmiany państwa")
                        Text("on/off", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cfg.musicEnabled, onCheckedChange = { on ->
                        vm.updateConfig { it.copy(musicEnabled = on) }
                    })
                }

                SliderRow("Długość pokazu zdjęcia", cfg.photoDisplaySec, 1f..30f, "s") {
                    vm.updateConfig { c -> c.copy(photoDisplaySec = it) }
                }
                SliderRow("Pokaz lokalizacji na mapie", cfg.locationMapSec, 2f..30f, "s") {
                    vm.updateConfig { c -> c.copy(locationMapSec = it) }
                }
                SliderRow("Wstęp mapa świata / państwo", cfg.worldMapSec, 2f..40f, "s") {
                    vm.updateConfig { c -> c.copy(worldMapSec = it) }
                }
                SliderRow("Przenikanie mapa–zdjęcie", cfg.transitionSec, 0.2f..5f, "s") {
                    vm.updateConfig { c -> c.copy(transitionSec = it) }
                }
                SliderRow("Minimalna odległość mapy lokalizacji", cfg.distanceKmThreshold.toFloat(), 1f..100f, "km") {
                    vm.updateConfig { c -> c.copy(distanceKmThreshold = it.toDouble()) }
                }
                SliderRow("Głośność muzyki", cfg.musicVolumePct.toFloat(), 0f..100f, "%") {
                    vm.updateConfig { c -> c.copy(musicVolumePct = it.toInt()) }
                }

                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Kolejność: od najnowszych")
                        Text("w przeciwnym razie od najstarszych", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = cfg.newestFirst, onCheckedChange = { on ->
                        vm.updateConfig { it.copy(newestFirst = on) }
                    })
                }

                Text(
                    "Sterowanie pokazu: tapnięcie — pasek sterowania, podwójne tapnięcie — pauza, przeciąganie mapy — przeglądanie kafelków.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(value, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action()
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title)
            Text(
                "${"%.1f".format(value)} $unit",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}