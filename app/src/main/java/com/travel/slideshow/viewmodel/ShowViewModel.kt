package com.travel.slideshow.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.travel.slideshow.data.PhotoRepository
import com.travel.slideshow.data.PlaceResolver
import com.travel.slideshow.data.geo.CountryInfo
import com.travel.slideshow.data.geo.CountryShape
import com.travel.slideshow.data.geo.Geo
import com.travel.slideshow.data.geo.WorldMapData
import com.travel.slideshow.media.MusicPlayer
import com.travel.slideshow.media.MusicResolver
import com.travel.slideshow.model.PhotoItem
import com.travel.slideshow.model.ShowConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min

sealed interface ShowStep {
    data object Idle : ShowStep
    data class Scanning(val count: Int) : ShowStep
    data class WorldCountry(val country: CountryShape, val zoomedIn: Boolean) : ShowStep
    data class LocationMap(val photo: PhotoItem, val revisit: Boolean = false) : ShowStep
    data class RouteMap(val from: PhotoItem, val photo: PhotoItem) : ShowStep
    data class Photo(val photo: PhotoItem) : ShowStep
    /**
     * Title card "Dzień N — place"; [localDayMs] is local midnight encoded as UTC, [photo] names the place,
     * [backdrop] is the day's first still photo, shown faded behind the text.
     */
    data class DayTitle(
        val dayNumber: Int,
        val localDayMs: Long,
        val photo: PhotoItem?,
        val backdrop: PhotoItem? = null,
    ) : ShowStep
    data object Finished : ShowStep
}

/** Reverse-geocoded town for [photo], once known. */
fun placeOf(photo: PhotoItem?, names: Map<String, String>): String? {
    val lat = photo?.lat ?: return null
    val lon = photo.lon ?: return null
    return names[PlaceResolver.key(lat, lon)]
}

data class RouteState(val uri: Uri, val points: List<List<Double>>)

data class PinState(val lat: Double, val lon: Double, val title: String? = null)

data class Segment(val step: ShowStep, val durMs: Long, val musicIso3: String? = null)

data class UiState(
    val step: ShowStep = ShowStep.Idle,
    val stopped: Boolean = false,
    val paused: Boolean = false,
    val itemCount: Int = 0,
    val withLocation: Int = 0,
    val videoCount: Int = 0,
    val config: ShowConfig = ShowConfig(),
    val settingsVisible: Boolean = false,
    val photoFolderName: String? = null,
    val musicFolderName: String? = null,
    val message: String? = null,
    val route: RouteState? = null,
    val pastRoutes: List<RouteState> = emptyList(),
    val visitedPins: List<PinState> = emptyList(),
    /** PlaceResolver.key → town name. */
    val placeNames: Map<String, String> = emptyMap(),
    val runId: Int = 0,
)

class ShowViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("show_prefs", Context.MODE_PRIVATE)
    private val repo = PhotoRepository(app)
    private val musicPlayer = MusicPlayer(app)
    private val musicResolver = MusicResolver(app)
    private val places = PlaceResolver(app)
    private var geocodeJob: Job? = null

    private val _state = MutableStateFlow(UiState(config = loadConfig()))
    val state: StateFlow<UiState> = _state

    private val pausedGate = MutableStateFlow(false)
    private val manualGate = MutableStateFlow(false)
    private var gestureJob: kotlinx.coroutines.Job? = null
    private val pastRoutesSet = HashSet<Uri>()
    private val visitedPinSet = HashSet<String>()
    private val stopScan = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastPhotos: List<PhotoItem> = emptyList()
    private var segments: List<Segment> = emptyList()
    private var segIdx = 0
    private var blockReplayUri: String? = null
    private var driver: Job? = null
    private var pausedBeforeSettings = false
    private var pausedForBackground = false
    private var pendingReset = false
    private var pendingRebuild = false
    private var musicRoot: Uri? = null
    private var musicJob: Job? = null
    private var currentMusicIso: String? = null
    // Touched only on the main thread; network work hops to IO and back.
    private val routeCache = HashMap<String, List<List<Double>>>()
    private val routeInFlight = HashSet<String>()

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
            .putBoolean(PREF_VIDEOS, cfg.showVideos)
            .putFloat(PREF_VIDEO_MAX, cfg.maxVideoSec)
            .putBoolean(PREF_DAY_TITLES, cfg.dayTitles)
            .putBoolean(PREF_PLACES, cfg.placeNames)
            .apply()
        musicPlayer.setVolume(cfg.musicVolumePct / 100f * 0.85f)
        if (old.placeNames != cfg.placeNames) {
            if (cfg.placeNames) startGeocoding() else geocodeJob?.cancel()
        }
        if (lastPhotos.isEmpty()) return
        // Segment changes are applied when settings close, not on every slider tick.
        if (old.onlyWithLocation != cfg.onlyWithLocation || old.newestFirst != cfg.newestFirst ||
            old.showVideos != cfg.showVideos
        ) {
            pendingReset = true
        } else if (old.photoDisplaySec != cfg.photoDisplaySec ||
            old.locationMapSec != cfg.locationMapSec ||
            old.worldMapSec != cfg.worldMapSec ||
            old.distanceKmThreshold != cfg.distanceKmThreshold ||
            old.maxVideoSec != cfg.maxVideoSec ||
            old.dayTitles != cfg.dayTitles
        ) {
            pendingRebuild = true
        }
        if (!_state.value.settingsVisible) applyPendingSegmentChanges()
    }

    private fun applyPendingSegmentChanges() {
        val reset = pendingReset
        val rebuild = pendingRebuild
        pendingReset = false
        pendingRebuild = false
        if (lastPhotos.isEmpty() || (!reset && !rebuild)) return
        if (reset) {
            driver?.cancel()
            stopMusic()
            lastPhotos = PhotoRepository.sortByDate(lastPhotos, _state.value.config.newestFirst)
            segments = buildSegments(lastPhotos)
            startGeocoding()
            segIdx = 0
            setPaused(false)
            resetTrail()
            _state.value = _state.value.copy(step = ShowStep.Idle, message = null)
            return
        }
        val photoUri = currentPhotoUri()
        val running = isRunning()
        segments = buildSegments(lastPhotos)
        startGeocoding()
        if (running && segments.isNotEmpty()) {
            blockReplayUri = null
            segIdx = segments.indexOfFirst { (it.step as? ShowStep.Photo)?.photo?.uri == photoUri }
                .coerceAtLeast(0)
            relaunch(syncMusic = true)
        }
    }

    /** Photo shown by the current segment, or the next one when on a map leading to it. */
    private fun currentPhotoUri(): Uri? =
        (segIdx until segments.size).firstNotNullOfOrNull { (segments[it].step as? ShowStep.Photo)?.photo?.uri }

    private fun isRunning(): Boolean = driver?.isActive == true

    fun setSettingsVisible(visible: Boolean) {
        if (visible) {
            pausedBeforeSettings = _state.value.paused
            if (isRunning() && !_state.value.paused) setPaused(true)
            _state.value = _state.value.copy(settingsVisible = true)
            return
        }
        _state.value = _state.value.copy(settingsVisible = false)
        applyPendingSegmentChanges()
        if (isRunning() && !pausedBeforeSettings && _state.value.paused) setPaused(false)
    }

    fun onAppBackground() {
        if (isRunning() && !_state.value.paused) {
            setPaused(true)
            pausedForBackground = true
        }
    }

    fun onAppForeground() {
        if (!pausedForBackground) return
        pausedForBackground = false
        if (_state.value.paused && !_state.value.settingsVisible) setPaused(false)
    }

    val manualHold: StateFlow<Boolean> get() = manualGate

    /** Map or photo gesture: the show holds until 5 s after the last one. */
    fun onUserGesture() {
        manualGate.value = true
        gestureJob?.cancel()
        gestureJob = viewModelScope.launch {
            kotlinx.coroutines.delay(5000)
            manualGate.value = false
        }
    }

    fun togglePause() {
        val nowPaused = !_state.value.paused
        // Taps on the idle/finished screen must not leave a pause that a later Start inherits.
        if (nowPaused && !isRunning()) return
        setPaused(nowPaused)
    }

    private fun setPaused(paused: Boolean) {
        pausedGate.value = paused
        _state.value = _state.value.copy(paused = paused)
        if (paused) musicPlayer.pause() else musicPlayer.resume()
    }

    fun start() { restartAll() }

    fun restartAll() {
        stopMusic()
        driver?.cancel()
        segIdx = 0
        setPaused(false)
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

    /** Jumps to the start of the next calendar day (its title card or the maps leading to it). */
    fun skipNextDay() {
        if (segments.isEmpty()) return
        val cur = dayAt(segIdx)
        val next = (segIdx + 1 until segments.size).firstOrNull { i ->
            photoDay(i)?.let { it != cur } == true
        } ?: return
        blockReplayUri = null
        jumpTo(dayStartIdx(next))
    }

    /** Jumps to the start of the previous calendar day; on the first day, back to its start. */
    fun skipPreviousDay() {
        if (segments.isEmpty()) return
        val cur = dayAt(segIdx)
        val photoIdx = (segIdx until segments.size).firstOrNull { segments[it].step is ShowStep.Photo }
            ?: prevPhotoIdx(segIdx) ?: return
        val curStart = dayStartIdx(photoIdx)
        val prev = (curStart - 1 downTo 0).firstOrNull { i -> photoDay(i)?.let { it != cur } == true }
        blockReplayUri = null
        jumpTo(prev?.let { dayStartIdx(it) } ?: curStart)
    }

    private fun photoDay(i: Int): Long? = (segments[i].step as? ShowStep.Photo)?.photo?.let { dayOf(it) }

    /** Day being shown at [i]: the card's day, or that of the next dated photo. */
    private fun dayAt(i: Int): Long? {
        (segments.getOrNull(i)?.step as? ShowStep.DayTitle)?.let { return it.localDayMs / DAY_MS }
        return (i until segments.size).firstNotNullOfOrNull { photoDay(it) }
    }

    /** First segment of the day run containing the photo at [photoIdx], including its card and lead-in maps. */
    private fun dayStartIdx(photoIdx: Int): Int {
        val day = photoDay(photoIdx)
        var first = photoIdx
        var i = photoIdx - 1
        while (i >= 0) {
            when (val st = segments[i].step) {
                is ShowStep.Photo -> {
                    val d = dayOf(st.photo)
                    if (d != null && day != null && d != day) break
                    first = i
                }
                is ShowStep.DayTitle -> {
                    if (day == null || st.localDayMs / DAY_MS == day) return i
                    break
                }
                else -> {}
            }
            i--
        }
        while (first > 0 && segments[first - 1].step.let {
                it is ShowStep.WorldCountry || it is ShowStep.LocationMap || it is ShowStep.RouteMap
            }
        ) first--
        return first
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
        stopMusic()
        driver?.cancel()
        segments = emptyList()
        lastPhotos = emptyList()
        geocodeJob?.cancel()
        segIdx = 0
        setPaused(false)
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
        pendingReset = false
        pendingRebuild = false
        resetTrail()
        viewModelScope.launch {
            message(null)
            _state.value = _state.value.copy(step = ShowStep.Scanning(0))
            WorldMapData.load(getApplication())
            val items = repo.scan(
                root,
                _state.value.config.newestFirst,
                shouldStop = { stopScan.get() },
            ) { n ->
                _state.update { it.copy(step = ShowStep.Scanning(n)) }
            }
            val stopped = stopScan.get()
            val located = items.count { it.iso3 != null }
            val videos = items.count { it.isVideo }
            _state.update { it.copy(itemCount = items.size, withLocation = located, videoCount = videos) }
            if (items.isEmpty()) {
                lastPhotos = emptyList()
                _state.update {
                    it.copy(
                        step = ShowStep.Idle,
                        message = if (stopped) "Skanowanie zatrzymano — brak zdjęć." else "Nie znaleziono zdjęć w wybranym folderze.",
                    )
                }
                return@launch
            }
            lastPhotos = items
            segments = buildSegments(items)
            startGeocoding()
            _state.update {
                it.copy(
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
    }

    fun stopScanning() {
        stopScan.set(true)
    }

    private fun message(text: String?) {
        _state.update { it.copy(message = text) }
    }

    private fun buildSegments(photos: List<PhotoItem>): List<Segment> {
        val cfg = _state.value.config
        val segs = mutableListOf<Segment>()
        val filtered = photos
            .filter { cfg.showVideos || !it.isVideo }
            .filter { !cfg.onlyWithLocation || (it.lat != null && it.lon != null) }
        val photoMs = (cfg.photoDisplaySec * 1000).toLong().coerceAtLeast(600)
        val maxVideoMs = (cfg.maxVideoSec * 1000).toLong().coerceAtLeast(1500)
        val locMs = (cfg.locationMapSec * 1000).toLong().coerceAtLeast(1200)
        val worldMs = (cfg.worldMapSec * 1000).toLong().coerceAtLeast(2000)
        val revisitMs = (locMs * 0.3).toLong().coerceAtLeast(1100)

        val travelSteps = mutableListOf<MutableList<Segment>>()
        val photoSegs = mutableListOf<MutableList<Segment>>()
        val anchors = mutableListOf<PhotoItem?>()
        travelSteps.add(mutableListOf())
        photoSegs.add(mutableListOf())
        anchors.add(null)
        var prevLat: Double? = null
        var prevLon: Double? = null
        var prevIso: String? = null
        for (p in filtered) {
            if (p.lat != null && p.lon != null) {
                val iso = p.iso3
                var isTravel = false
                if (iso != null && iso != prevIso) {
                    val shape = WorldMapData.byIso(iso)
                    travelSteps.add(mutableListOf())
                    if (shape != null) {
                        travelSteps.last().add(Segment(ShowStep.WorldCountry(shape, false), (worldMs * 0.35).toLong(), iso))
                        travelSteps.last().add(Segment(ShowStep.WorldCountry(shape, true), (worldMs * 0.65).toLong()))
                    }
                    travelSteps.last().add(Segment(ShowStep.LocationMap(p), locMs))
                    isTravel = true
                } else {
                    val d = if (prevLat != null && prevLon != null) {
                        Geo.haversineKm(prevLat, prevLon, p.lat, p.lon)
                    } else Double.MAX_VALUE
                    if (d >= cfg.distanceKmThreshold && anchors.last() != null) {
                        travelSteps.add(mutableListOf(Segment(ShowStep.RouteMap(anchors.last()!!, p), locMs)))
                        isTravel = true
                    }
                }
                if (isTravel) {
                    photoSegs.add(mutableListOf())
                    anchors.add(null)
                }
                prevLat = p.lat
                prevLon = p.lon
            }
            val dur = if (p.isVideo) (p.durationMs ?: maxVideoMs).coerceIn(1500, maxVideoMs) else photoMs
            photoSegs.last().add(Segment(ShowStep.Photo(p), dur))
            if (p.iso3 != null) prevIso = p.iso3
            if (p.lat != null && p.lon != null) anchors[anchors.size - 1] = p
        }
        for (i in 0 until travelSteps.size) {
            segs += travelSteps[i]
            segs += photoSegs[i]
            if (i + 1 < travelSteps.size && anchors[i] != null && travelSteps[i + 1].isNotEmpty()) {
                segs += Segment(ShowStep.LocationMap(anchors[i]!!, revisit = true), revisitMs)
            }
        }
        return if (cfg.dayTitles) withDayTitles(segs, filtered) else segs
    }

    private fun dayOf(p: PhotoItem): Long? = p.localTimeMs?.let { Math.floorDiv(it, DAY_MS) }

    /**
     * Inserts a "Dzień N" card whenever the calendar day changes. The card goes before the maps
     * leading to the day's first photo, so the trip to a new place is introduced by its day.
     */
    private fun withDayTitles(segs: List<Segment>, photos: List<PhotoItem>): List<Segment> {
        val days = photos.mapNotNull { dayOf(it) }.toSortedSet()
        if (days.size < 2) return segs
        // Numbering restarts after a long gap: separate trips in one folder each start at day 1.
        val dayNumber = HashMap<Long, Int>()
        var tripStart = days.first()
        var prev = days.first()
        for (d in days) {
            if (d - prev > TRIP_GAP_DAYS) tripStart = d
            dayNumber[d] = (d - tripStart + 1).toInt()
            prev = d
        }
        val placeFor = HashMap<Long, PhotoItem>()
        val backdropFor = HashMap<Long, PhotoItem>()
        for (p in photos) {
            val d = dayOf(p) ?: continue
            if (p.lat != null && p.lon != null && d !in placeFor) placeFor[d] = p
            if (!p.isVideo && d !in backdropFor) backdropFor[d] = p
        }
        val out = ArrayList<Segment>(segs.size + days.size)
        var lastDay: Long? = null
        for (seg in segs) {
            val p = (seg.step as? ShowStep.Photo)?.photo
            val d = p?.let { dayOf(it) }
            if (d != null && d != lastDay) {
                var at = out.size
                while (at > 0 && out[at - 1].step.let {
                        it is ShowStep.WorldCountry || it is ShowStep.LocationMap || it is ShowStep.RouteMap
                    }
                ) at--
                out.add(at, Segment(ShowStep.DayTitle(dayNumber[d] ?: 1, d * DAY_MS, placeFor[d], backdropFor[d]), DAY_TITLE_MS))
                lastDay = d
            }
            out += seg
        }
        return out
    }

    /** Looks up town names for every located photo in show order; results stream into [UiState.placeNames]. */
    private fun startGeocoding() {
        geocodeJob?.cancel()
        if (!_state.value.config.placeNames) return
        val wanted = LinkedHashMap<String, PhotoItem>()
        for (seg in segments) {
            val p = when (val st = seg.step) {
                is ShowStep.Photo -> st.photo
                is ShowStep.LocationMap -> st.photo
                is ShowStep.RouteMap -> st.photo
                is ShowStep.DayTitle -> st.photo
                else -> null
            } ?: continue
            val lat = p.lat ?: continue
            val lon = p.lon ?: continue
            wanted.putIfAbsent(PlaceResolver.key(lat, lon), p)
        }
        if (wanted.isEmpty()) return
        geocodeJob = viewModelScope.launch {
            val cached = places.cachedMany(wanted.keys)
            val known = cached.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
            _state.update { it.copy(placeNames = it.placeNames + known) }
            for ((k, p) in wanted) {
                if (k in cached) continue
                val name = places.resolve(p.lat!!, p.lon!!) ?: continue
                _state.update { it.copy(placeNames = it.placeNames + (k to name)) }
            }
        }
    }

    private fun routeKey(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String =
        "%.5f,%.5f|%.5f,%.5f".format(lat1, lon1, lat2, lon2)

    private fun routeKey(step: ShowStep.RouteMap): String? {
        val from = step.from
        val to = step.photo
        if (from.lat == null || from.lon == null || to.lat == null || to.lon == null) return null
        return routeKey(from.lat, from.lon, to.lat, to.lon)
    }

    /** Only fetches and caches; the route joins the trail once the map arrives (see [onArrived]). */
    private fun fetchRoute(stepIn: ShowStep.RouteMap) {
        val from = stepIn.from
        val to = stepIn.photo
        val key = routeKey(stepIn) ?: return
        val cached = routeCache[key]
        if (cached != null) {
            _state.update { it.copy(route = RouteState(to.uri, cached)) }
            return
        }
        if (!routeInFlight.add(key)) return
        viewModelScope.launch {
            val pts = withContext(Dispatchers.IO) { osrmRoute(from.lat!!, from.lon!!, to.lat!!, to.lon!!) }
                ?: listOf(listOf(from.lon!!, from.lat!!), listOf(to.lon!!, to.lat!!))
            routeInFlight.remove(key)
            routeCache[key] = pts
            _state.update { it.copy(route = RouteState(to.uri, pts)) }
        }
    }

    private fun addPastRoute(uri: Uri, pts: List<List<Double>>) {
        _state.update { cur ->
            if (cur.pastRoutes.any { it.uri == uri } || uri in pastRoutesSet) cur
            else cur.copy(pastRoutes = cur.pastRoutes + RouteState(uri, pts))
        }
        pastRoutesSet.add(uri)
    }

    /** Called by the map when a location/route step reaches its destination pin. */
    fun onArrived(step: ShowStep) {
        val photo = when (step) {
            is ShowStep.LocationMap -> step.photo
            is ShowStep.RouteMap -> {
                routeKey(step)?.let { routeCache[it] }?.let { addPastRoute(step.photo.uri, it) }
                step.photo
            }
            else -> return
        }
        markVisited(photo)
    }

    private fun markVisited(photo: PhotoItem) {
        val pin = pinFor(photo) ?: return
        if (visitedPinSet.add(pinKey(pin))) {
            _state.update { it.copy(visitedPins = it.visitedPins + pin) }
        }
    }

    private fun pinFor(photo: PhotoItem): PinState? {
        val lat = photo.lat ?: return null
        val lon = photo.lon ?: return null
        val title = (photo.iso3?.let { CountryInfo.flagEmoji(it) + " " } ?: "") + (photo.countryName ?: "")
        return PinState(lat, lon, title)
    }

    private fun pinKey(pin: PinState): String = "%.5f,%.5f".format(pin.lat, pin.lon)

    private fun resetTrail() = rebuildTrail(0)

    /** Trail (routes + pins) as it would look after playing segments [0, upTo). */
    private fun rebuildTrail(upTo: Int) {
        pastRoutesSet.clear()
        visitedPinSet.clear()
        val routes = mutableListOf<RouteState>()
        val pins = mutableListOf<PinState>()
        for (i in 0 until min(upTo, segments.size)) {
            val photo = when (val st = segments[i].step) {
                is ShowStep.LocationMap -> st.photo
                is ShowStep.RouteMap -> {
                    routeKey(st)?.let { routeCache[it] }?.let { pts ->
                        if (pastRoutesSet.add(st.photo.uri)) routes += RouteState(st.photo.uri, pts)
                    }
                    st.photo
                }
                else -> null
            } ?: continue
            val pin = pinFor(photo) ?: continue
            if (visitedPinSet.add(pinKey(pin))) pins += pin
        }
        _state.update { it.copy(pastRoutes = routes, visitedPins = pins, route = null) }
    }

    private fun playMusicFor(iso: String, force: Boolean) {
        if (!_state.value.config.musicEnabled) return
        if (!force && iso == currentMusicIso) return
        currentMusicIso = iso
        musicJob?.cancel()
        musicJob = viewModelScope.launch {
            val name = WorldMapData.byIso(iso)?.name ?: ""
            val tracks = withContext(Dispatchers.IO) { musicResolver.tracksFor(iso, name, musicRoot) }
            // Country change: the old song fades out under the new one instead of cutting off.
            musicPlayer.play(tracks, crossfade = true)
        }
    }

    private fun stopMusic() {
        musicJob?.cancel()
        musicJob = null
        currentMusicIso = null
        musicPlayer.stop()
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
        if (nextIdx >= segments.size) nextIdx = 0
        rebuildTrail(nextIdx)
        val startedRunId = _state.value.runId + 1
        _state.value = _state.value.copy(runId = startedRunId)
        musicPlayer.setVolume(_state.value.config.musicVolumePct / 100f * 0.85f)
        driver = viewModelScope.launch {
            _state.value = _state.value.copy(stopped = false)
            var first = true
            for (i in nextIdx until segments.size) {
                segIdx = i
                val seg = segments[i]
                if (seg.step is ShowStep.RouteMap) fetchRoute(seg.step as ShowStep.RouteMap)
                (segments.getOrNull(i + 1)?.step as? ShowStep.RouteMap)?.let { if (it.photo.uri != (seg.step as? ShowStep.RouteMap)?.photo?.uri) fetchRoute(it) }
                if (first) {
                    first = false
                    if (syncMusic && seg.musicIso3 == null) {
                        val iso = when (val st = seg.step) {
                            is ShowStep.Photo -> st.photo.iso3
                            is ShowStep.LocationMap -> st.photo.iso3
                            is ShowStep.RouteMap -> st.photo.iso3
                            is ShowStep.DayTitle -> st.photo?.iso3
                            else -> null
                        }
                        // Jumps inside the same country keep the current track.
                        if (iso != null) playMusicFor(iso, force = false)
                    }
                }
                if (seg.musicIso3 != null) playMusicFor(seg.musicIso3, force = true)
                // Clips have their own sound; the music steps back while one plays.
                musicPlayer.setDucked((seg.step as? ShowStep.Photo)?.photo?.isVideo == true)
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
        // skipNext() can finish the show from outside the driver; stop it from overwriting the step.
        driver?.cancel()
        _state.value = _state.value.copy(stopped = true, step = ShowStep.Finished)
        musicPlayer.setDucked(false)
        stopMusic()
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
            showVideos = prefs.getBoolean(PREF_VIDEOS, true),
            maxVideoSec = prefs.getFloat(PREF_VIDEO_MAX, 20f),
            dayTitles = prefs.getBoolean(PREF_DAY_TITLES, true),
            placeNames = prefs.getBoolean(PREF_PLACES, true),
        )
    }

    override fun onCleared() {
        driver?.cancel()
        stopMusic()
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
        private const val PREF_VIDEOS = "videos"
        private const val PREF_VIDEO_MAX = "video_max_sec"
        private const val PREF_DAY_TITLES = "day_titles"
        private const val PREF_PLACES = "place_names"
        private const val DAY_MS = 86_400_000L
        private const val DAY_TITLE_MS = 3200L
        /** A gap of more days than this without photos starts a new trip (day numbering restarts). */
        private const val TRIP_GAP_DAYS = 3
    }
}