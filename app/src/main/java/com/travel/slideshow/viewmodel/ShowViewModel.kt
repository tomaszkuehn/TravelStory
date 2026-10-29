package com.travel.slideshow.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travel.slideshow.data.PhotoRepository
import com.travel.slideshow.data.geo.CountryShape
import com.travel.slideshow.data.geo.Geo
import com.travel.slideshow.data.geo.WorldMapData
import com.travel.slideshow.media.MusicPlayer
import com.travel.slideshow.media.MusicResolver
import com.travel.slideshow.model.PhotoItem
import com.travel.slideshow.model.ShowConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min

sealed interface ShowStep {
    data object Idle : ShowStep
    data class Scanning(val count: Int) : ShowStep
    data class WorldCountry(val country: CountryShape, val zoomedIn: Boolean) : ShowStep
    data class LocationMap(val photo: PhotoItem) : ShowStep
    data class RouteMap(val from: PhotoItem, val photo: PhotoItem) : ShowStep
    data class Photo(val photo: PhotoItem) : ShowStep
    data object Finished : ShowStep
}

data class RouteState(val uri: Uri, val points: List<List<Double>>)

data class Segment(val step: ShowStep, val durMs: Long, val musicIso3: String? = null)

data class UiState(
    val step: ShowStep = ShowStep.Idle,
    val stopped: Boolean = false,
    val paused: Boolean = false,
    val itemCount: Int = 0,
    val withLocation: Int = 0,
    val config: ShowConfig = ShowConfig(),
    val settingsVisible: Boolean = false,
    val photoFolderName: String? = null,
    val musicFolderName: String? = null,
    val message: String? = null,
    val route: RouteState? = null,
    val pastRoutes: List<RouteState> = emptyList(),
    val runId: Int = 0,
)

class ShowViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("show_prefs", Context.MODE_PRIVATE)
    private val repo = PhotoRepository(app)
    private val musicPlayer = MusicPlayer(app)
    private val musicResolver = MusicResolver(app)

    private val _state = MutableStateFlow(UiState(config = loadConfig()))
    val state: StateFlow<UiState> = _state

    private val pausedGate = MutableStateFlow(false)
    private val manualGate = MutableStateFlow(false)
    private var gestureJob: kotlinx.coroutines.Job? = null
    private val pastRoutesSet = HashSet<Uri>()
    private val stopScan = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastPhotos: List<PhotoItem> = emptyList()
    private var segments: List<Segment> = emptyList()
    private var segIdx = 0
    private var blockReplayUri: String? = null
    private var driver: Job? = null
    private var pausedBeforeSettings = false
    private var musicRoot: Uri? = null
    private val routeCache = HashMap<String, List<List<Double>>>()

    init {
        musicRoot = prefs.getString(PREF_MUSIC_ROOT, null)?.toUriOrNull()
        _state.value = _state.value.copy(
            config = _state.value.config.copy(musicFolderUri = musicRoot),
            photoFolderName = prefs.getString(PREF_PHOTO_ROOT, null)?.let { folderName(it.toUriOrNull()) },
            musicFolderName = folderName(musicRoot),
        )
    }

    private fun String.toUriOrNull(): Uri? = runCatching { Uri.parse(this) }.getOrNull()

    private fun folderName(uri: Uri?): String? = runCatching {
        DocumentFile.fromTreeUri(getApplication(), uri ?: return null)?.name
    }.getOrNull()

    fun updateConfig(transform: (ShowConfig) -> ShowConfig) {
        val old = _state.value.config
        val cfg = transform(old)
        _state.value = _state.value.copy(config = cfg)
        prefs.edit()
            .putFloat(PREF_PHOTO_SEC, cfg.photoDisplaySec)
            .putFloat(PREF_LOC_SEC, cfg.locationMapSec)
            .putFloat(PREF_WORLD_SEC, cfg.worldMapSec)
            .putFloat(PREF_DIST, cfg.distanceKmThreshold.toFloat())
            .putBoolean(PREF_MUSIC_ON, cfg.musicEnabled)
            .putInt(PREF_VOLUME, cfg.musicVolumePct)
            .putBoolean(PREF_DESC, cfg.newestFirst)
            .putBoolean(PREF_ONLY_LOC, cfg.onlyWithLocation)
            .putFloat(PREF_TRANSITION, cfg.transitionSec)
            .apply()
        musicPlayer.setVolume(cfg.musicVolumePct / 100f * 0.85f)
        if (lastPhotos.isNotEmpty() &&
            (old.onlyWithLocation != cfg.onlyWithLocation || old.newestFirst != cfg.newestFirst)
        ) {
            driver?.cancel()
            musicPlayer.stop()
            segments = buildSegments(lastPhotos)
            segIdx = 0
            pausedGate.value = false
            _state.value = _state.value.copy(paused = false, step = ShowStep.Idle, message = null)
        }
    }

    fun setSettingsVisible(visible: Boolean) {
        if (visible) {
            pausedBeforeSettings = _state.value.paused
            if (!_state.value.paused) togglePause()
        }
        _state.value = _state.value.copy(settingsVisible = visible)
        if (!visible && pausedBeforeSettings && _state.value.paused) {
            restoreAfterSettings()
        }
    }

    private fun restoreAfterSettings() {
        togglePause()
    }

    val manualHold: StateFlow<Boolean> get() = manualGate

    fun onMapGesture() {
        manualGate.value = true
        gestureJob?.cancel()
        gestureJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            manualGate.value = false
        }
    }

    fun togglePause() {
        val nowPaused = !_state.value.paused
        pausedGate.value = nowPaused
        _state.value = _state.value.copy(paused = nowPaused)
        if (nowPaused) musicPlayer.pause() else musicPlayer.resume()
    }

    fun start() { restartAll() }

    fun restartAll() {
        musicPlayer.stop()
        driver?.cancel()
        segIdx = 0
        relaunch()
    }

    fun restartCountry() {
        if (segments.isEmpty()) return
        var i = min(segIdx, segments.size - 1)
        while (i > 0 && segments[i].musicIso3 == null) i--
        if (segments[i].musicIso3 == null) i = 0
        segIdx = i
        relaunch()
    }

    fun skipNext() {
        if (segments.isEmpty()) return
        blockReplayUri = null
        val next = (segIdx + 1 until segments.size).firstOrNull { segments[it].step is ShowStep.Photo }
        if (next == null) { setFinished(); return }
        jumpTo(next)
    }

    fun skipPrevious() {
        if (segments.isEmpty()) return
        val cur = segments.getOrNull(segIdx)?.step
        val target: Int? = when {
            cur is ShowStep.Photo && blockReplayUri == cur.photo.uri.toString() -> {
                blockReplayUri = null
                prevPhotoIdx(segIdx)
            }
            cur is ShowStep.Photo -> {
                val bs = blockStartOf(segIdx, cur.photo.uri)
                if (bs != null) {
                    blockReplayUri = cur.photo.uri.toString()
                    bs
                } else {
                    blockReplayUri = null
                    prevPhotoIdx(segIdx)
                }
            }
            else -> {
                blockReplayUri = null
                prevPhotoIdx(segIdx)
            }
        }
        target?.let { jumpTo(it) }
    }

    private fun prevPhotoIdx(from: Int): Int? =
        ((from - 1) downTo 0).firstOrNull { segments[it].step is ShowStep.Photo }

    private fun blockStartOf(photoIdx: Int, photoUri: Uri): Int? {
        val photo = (segments[photoIdx].step as? ShowStep.Photo)?.photo ?: return null
        var i = photoIdx - 1
        var start: Int? = null
        while (i >= 0) {
            val belongs = when (val st = segments[i].step) {
                is ShowStep.LocationMap -> st.photo.uri == photoUri
                is ShowStep.RouteMap -> st.photo.uri == photoUri
                is ShowStep.WorldCountry -> photo.iso3 != null && st.country.iso3 == photo.iso3
                else -> false
            }
            if (!belongs) break
            start = i
            i--
        }
        return start
    }

    private fun jumpTo(idx: Int) {
        segIdx = idx
        relaunch(syncMusic = true)
    }

    fun onPhotoFolderPicked(uri: Uri) {
        try {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(PREF_PHOTO_ROOT, uri.toString()).apply()
        _state.value = _state.value.copy(
            config = _state.value.config.copy(photoFolderUri = uri),
            photoFolderName = folderName(uri),
        )
        musicPlayer.stop()
        driver?.cancel()
        segments = emptyList()
        segIdx = 0
        _state.value = _state.value.copy(step = ShowStep.Idle, itemCount = 0)
    }

    fun onMusicFolderPicked(uri: Uri?) {
        try {
            if (uri != null) getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(PREF_MUSIC_ROOT, uri?.toString()).apply()
        musicRoot = uri
        _state.value = _state.value.copy(
            config = _state.value.config.copy(musicFolderUri = uri),
            musicFolderName = folderName(uri),
        )
    }

    fun prepare() {
        val root = _state.value.config.photoFolderUri ?: return
        stopScan.set(false)
        pastRoutesSet.clear()
        viewModelScope.launch {
            message(null)
            _state.value = _state.value.copy(step = ShowStep.Scanning(0), pastRoutes = emptyList(), route = null)
            WorldMapData.load(getApplication())
            val items = repo.scan(
                root,
                _state.value.config.newestFirst,
                shouldStop = { stopScan.get() },
            ) { n ->
                _state.value = _state.value.copy(step = ShowStep.Scanning(n))
            }
            val stopped = stopScan.get()
            val located = items.count { it.iso3 != null }
            _state.value = _state.value.copy(itemCount = items.size, withLocation = located)
            if (items.isEmpty()) {
                lastPhotos = emptyList()
                _state.value = _state.value.copy(
                    step = ShowStep.Idle,
                    message = if (stopped) "Skanowanie zatrzymano — brak zdjęć." else "Nie znaleziono zdjęć w wybranym folderze.",
                )
                return@launch
            }
            lastPhotos = items
            segments = buildSegments(items)
            _state.value = _state.value.copy(
                step = ShowStep.Idle,
                itemCount = items.size,
                withLocation = located,
                message = when {
                    segments.isEmpty() -> "Brak zdjęć spełniających ustawienia (np. wszystkie bez lokalizacji)."
                    stopped -> "Skanowanie zatrzymano — pokaz zawiera ${items.size} przeskanowanych zdjęć."
                    else -> null
                },
            )
        }
    }

    fun stopScanning() {
        stopScan.set(true)
    }

    private fun message(text: String?) {
        _state.value = _state.value.copy(message = text)
    }

    private fun buildSegments(photos: List<PhotoItem>): List<Segment> {
        val cfg = _state.value.config
        val segs = mutableListOf<Segment>()
        val filtered =
            if (cfg.onlyWithLocation) photos.filter { it.lat != null && it.lon != null } else photos
        val photoMs = (cfg.photoDisplaySec * 1000).toLong().coerceAtLeast(600)
        val locMs = (cfg.locationMapSec * 1000).toLong().coerceAtLeast(1200)
        val worldMs = (cfg.worldMapSec * 1000).toLong().coerceAtLeast(2000)
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevIso: String? = null
        var prevPhoto: PhotoItem? = null
        for (p in filtered) {
            var musicIso: String? = null
            if (p.lat != null && p.lon != null) {
                val iso = p.iso3
                if (iso != null && iso != prevIso) {
                    musicIso = iso
                    val shape = WorldMapData.byIso(iso)
                    if (shape != null) {
                        segs += Segment(ShowStep.WorldCountry(shape, false), (worldMs * 0.35).toLong(), musicIso)
                        segs += Segment(ShowStep.WorldCountry(shape, true), (worldMs * 0.65).toLong())
                    }
                    segs += Segment(ShowStep.LocationMap(p), locMs)
                } else {
                    val d = if (prevLat != null && prevLon != null) {
                        Geo.haversineKm(prevLat, prevLon, p.lat, p.lon)
                    } else Double.MAX_VALUE
                    when {
                        d >= cfg.distanceKmThreshold ->
                            segs += Segment(ShowStep.LocationMap(p), locMs)
                        d > ROUTE_MIN_KM && prevPhoto != null ->
                            segs += Segment(ShowStep.RouteMap(prevPhoto!!, p), locMs)
                    }
                }
                prevLat = p.lat
                prevLon = p.lon
                prevPhoto = p
            }
            segs += Segment(ShowStep.Photo(p), photoMs)
            if (p.iso3 != null) prevIso = p.iso3
        }
        return segs
    }

    private fun routeKey(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String =
        "%.5f,%.5f|%.5f,%.5f".format(lat1, lon1, lat2, lon2)

    private suspend fun fetchRoute(stepIn: ShowStep.RouteMap) {
        val from = stepIn.from
        val to = stepIn.photo
        if (from.lat == null || from.lon == null || to.lat == null || to.lon == null) return
        val key = routeKey(from.lat, from.lon, to.lat, to.lon)
        val cached = routeCache[key]
        val pts = cached ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            osrmRoute(from.lat, from.lon, to.lat, to.lon)
        } ?: listOf(listOf(from.lon, from.lat), listOf(to.lon, to.lat))
        routeCache[key] = pts
        addPastRoute(to.uri, pts)
        _state.value = _state.value.copy(route = RouteState(to.uri, pts))
    }

    private fun addPastRoute(uri: Uri, pts: List<List<Double>>) {
        val cur = _state.value
        if (cur.pastRoutes.any { it.uri == uri } || uri in pastRoutesSet) return
        pastRoutesSet.add(uri)
        _state.value = cur.copy(pastRoutes = cur.pastRoutes + RouteState(uri, pts))
    }

    private fun osrmRoute(lat1: Double, lon1: Double, lat2: Double, lon2: Double): List<List<Double>>? {
        return try {
            val url = java.net.URL(
                "https://router.project-osrm.org/route/v1/driving/" +
                    "%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson"
                    .format(lon1, lat1, lon2, lat2)
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TravelSlideshow/1.0")
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            conn.disconnect()
            val root = JSONObject(body)
            val routes = root.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null
            val coords = routes.getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
            val out = mutableListOf<List<Double>>()
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                out.add(listOf(c.getDouble(0), c.getDouble(1)))
            }
            if (out.size < 2) null else out
        } catch (e: Exception) {
            null
        }
    }

    private fun relaunch(syncMusic: Boolean = false) {
        var nextIdx = segIdx
        driver?.cancel()
        val startedRunId = _state.value.runId + 1
        _state.value = _state.value.copy(runId = startedRunId)
        musicPlayer.setVolume(_state.value.config.musicVolumePct / 100f * 0.85f)
        driver = viewModelScope.launch {
            _state.value = _state.value.copy(stopped = false)
            if (nextIdx >= segments.size) nextIdx = 0
            var first = true
            for (i in nextIdx until segments.size) {
                segIdx = i
                val seg = segments[i]
                if (seg.step is ShowStep.RouteMap) fetchRoute(seg.step as ShowStep.RouteMap)
                if (first) {
                    first = false
                    if (syncMusic) {
                        val iso = (seg.step as? ShowStep.Photo)?.photo?.iso3
                        if (iso != null && _state.value.config.musicEnabled) {
                            val shape = WorldMapData.byIso(iso)
                            val name = shape?.name ?: ""
                            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                musicPlayer.play(musicResolver.tracksFor(iso, name, musicRoot))
                            }
                        }
                    }
                }
                if (seg.musicIso3 != null && _state.value.config.musicEnabled) {
                    val iso = seg.musicIso3
                    val shape = WorldMapData.byIso(iso)
                    val name = shape?.name ?: ""
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        musicPlayer.play(musicResolver.tracksFor(iso, name, musicRoot))
                    }
                }
                _state.value = _state.value.copy(message = null, step = seg.step)
                pausableDelay(seg.durMs)
            }
            setFinished()
        }
    }

    private suspend fun pausableDelay(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            if (pausedGate.value) {
                pausedGate.first { !it }
                continue
            }
            if (manualGate.value) {
                manualGate.first { !it }
                continue
            }
            val chunk = min(80, remaining)
            delay(chunk)
            remaining -= chunk
        }
    }

    private fun setFinished() {
        _state.value = _state.value.copy(stopped = true, step = ShowStep.Finished)
        musicPlayer.stop()
    }

    private fun loadConfig(): ShowConfig {
        val photo = prefs.getString(PREF_PHOTO_ROOT, null)?.toUriOrNull()
        val music = prefs.getString(PREF_MUSIC_ROOT, null)?.toUriOrNull()
        return ShowConfig(
            photoFolderUri = photo,
            musicFolderUri = music,
            photoDisplaySec = prefs.getFloat(PREF_PHOTO_SEC, 5f),
            locationMapSec = prefs.getFloat(PREF_LOC_SEC, 6f),
            worldMapSec = prefs.getFloat(PREF_WORLD_SEC, 9f),
            distanceKmThreshold = prefs.getFloat(PREF_DIST, 5f).toDouble(),
            musicEnabled = prefs.getBoolean(PREF_MUSIC_ON, true),
            musicVolumePct = prefs.getInt(PREF_VOLUME, 80),
            newestFirst = prefs.getBoolean(PREF_DESC, false),
            onlyWithLocation = prefs.getBoolean(PREF_ONLY_LOC, false),
            transitionSec = prefs.getFloat(PREF_TRANSITION, 1f),
        )
    }

    override fun onCleared() {
        driver?.cancel()
        musicPlayer.stop()
        super.onCleared()
    }

    companion object {
        private const val PREF_PHOTO_ROOT = "photo_root"
        private const val PREF_MUSIC_ROOT = "music_root"
        private const val PREF_PHOTO_SEC = "photo_sec"
        private const val PREF_LOC_SEC = "loc_sec"
        private const val PREF_WORLD_SEC = "world_sec"
        private const val PREF_DIST = "dist_km"
        private const val PREF_MUSIC_ON = "music_on"
        private const val PREF_VOLUME = "volume"
        private const val PREF_DESC = "desc"
        private const val PREF_ONLY_LOC = "only_loc"
        private const val PREF_TRANSITION = "transition_sec"
        private const val ROUTE_MIN_KM = 1.0
    }
}