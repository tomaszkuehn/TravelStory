package com.travel.slideshow.data

import android.content.Context
import com.travel.slideshow.data.geo.Geo
import com.travel.slideshow.model.TravelMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A leg between two photos as [lon, lat] points: a road route, or a flight arc.
 * [head]/[tail]: off-road bits from the photo to where the road starts / from where it ends (empty when on it).
 */
data class Route(
    val points: List<List<Double>>,
    val flight: Boolean,
    val head: List<List<Double>> = emptyList(),
    val tail: List<List<Double>> = emptyList(),
)

/**
 * Road routes from public OSRM servers (car: project-osrm.org, bike/foot: routing.openstreetmap.de),
 * simplified and cached on disk (one file per leg and travel mode in `files/routes/`).
 * Long hops, and places OSRM cannot connect by road (islands, other continents), become a flight arc.
 * Respects the demo server policy: one request per second, identifying User-Agent.
 */
class RouteResolver(context: Context) {

    private val dir = File(context.filesDir, "routes")
    private val lock = Mutex()
    private var lastRequestAt = 0L

    /** The leg from 1 to 2; null when OSRM could not be asked (offline, rate limit) — not cached, retried later. */
    suspend fun resolve(mode: TravelMode, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route? = lock.withLock {
        if (Geo.haversineKm(lat1, lon1, lat2, lon2) >= FLIGHT_KM) return@withLock flightArc(lat1, lon1, lat2, lon2)
        val file = File(dir, fileName(mode, lat1, lon1, lat2, lon2))
        // Simplifying again on read also slims legs cached in full by older versions.
        val road = withContext(Dispatchers.IO) { read(file)?.let(::simplify) } ?: run {
            val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
            val fetched = withContext(Dispatchers.IO) { fetch(mode, lat1, lon1, lat2, lon2) } ?: return@withLock null
            simplify(fetched).also { withContext(Dispatchers.IO) { write(file, it) } }
        }
        if (road.isEmpty()) flightArc(lat1, lon1, lat2, lon2) else roadRoute(road, lat1, lon1, lat2, lon2)
    }

    private fun roadRoute(road: List<List<Double>>, lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route {
        val first = road.first()
        val last = road.last()
        val head = if (Geo.haversineKm(lat1, lon1, first[1], first[0]) * 1000 > OFF_ROAD_M) {
            listOf(listOf(lon1, lat1), first)
        } else emptyList()
        val tail = if (Geo.haversineKm(last[1], last[0], lat2, lon2) * 1000 > OFF_ROAD_M) {
            listOf(last, listOf(lon2, lat2))
        } else emptyList()
        return Route(road, false, head, tail)
    }

    /** Cached road points; an empty list marks "no road here, fly". Null when never asked. */
    private fun read(file: File): List<List<Double>>? = runCatching {
        if (!file.exists()) return null
        val text = file.readText()
        if (text == FLIGHT_MARK) return emptyList()
        text.split(';').map { p ->
            val (lon, lat) = p.split(',')
            listOf(lon.toDouble(), lat.toDouble())
        }.takeIf { it.size >= 2 }
    }.getOrNull()

    private fun write(file: File, road: List<List<Double>>) {
        runCatching {
            dir.mkdirs()
            val text = if (road.isEmpty()) FLIGHT_MARK
            else road.joinToString(";") { String.format(Locale.US, "%.5f,%.5f", it[0], it[1]) }
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text)
            tmp.renameTo(file)
        }
    }

    /** Road points; empty when there is no sensible road between the two; null on a network failure. */
    private fun fetch(mode: TravelMode, lat1: Double, lon1: Double, lat2: Double, lon2: Double): List<List<Double>>? {
        return try {
            val base = when (mode) {
                TravelMode.CAR -> "https://router.project-osrm.org/route/v1/driving/"
                TravelMode.BIKE -> "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
                TravelMode.FOOT -> "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
            }
            val url = java.net.URL(
                base + String.format(
                    Locale.US,
                    "%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                    lon1, lat1, lon2, lat2,
                )
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("User-Agent", "TravelSlideshow/1.0 (Android; personal photo slideshow)")
            val code = conn.responseCode
            // OSRM answers "no route" with HTTP 400 and a code in the body; 429/5xx are transient.
            val stream = if (code == 200) conn.inputStream else if (code == 400) conn.errorStream else null
            val body = stream?.use { it.readBytes().decodeToString() }
            conn.disconnect()
            if (body == null) return null
            val root = JSONObject(body)
            when (root.optString("code")) {
                "Ok" -> {}
                "NoRoute", "NoSegment" -> return emptyList()
                else -> return null
            }
            val routes = root.optJSONArray("routes") ?: return emptyList()
            if (routes.length() == 0) return emptyList()
            // Snapped far from the photo (no roads there) → the road would end somewhere else.
            val waypoints = root.optJSONArray("waypoints")
            if (waypoints != null) {
                for (i in 0 until waypoints.length()) {
                    if (waypoints.getJSONObject(i).optDouble("distance", 0.0) > MAX_SNAP_M) return emptyList()
                }
            }
            val route = routes.getJSONObject(0)
            // A drive many times the straight distance goes around a sea or a mountain range: fly instead.
            val straightKm = Geo.haversineKm(lat1, lon1, lat2, lon2)
            if (straightKm >= DETOUR_MIN_KM && route.optDouble("distance", 0.0) / 1000.0 > straightKm * MAX_DETOUR) {
                return emptyList()
            }
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val out = ArrayList<List<Double>>(coords.length())
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                out.add(listOf(c.getDouble(0), c.getDouble(1)))
            }
            if (out.size < 2) emptyList() else out
        } catch (e: Exception) {
            null
        }
    }

    // Car legs keep the unprefixed names they were first cached under.
    private fun fileName(mode: TravelMode, lat1: Double, lon1: Double, lat2: Double, lon2: Double): String =
        (if (mode == TravelMode.CAR) "" else mode.name.lowercase() + "_") +
            String.format(Locale.US, "%.5f_%.5f_%.5f_%.5f.txt", lat1, lon1, lat2, lon2)

    /**
     * Douglas-Peucker with a tolerance well under a pixel at the leg's travel zoom: the shape stays,
     * but a long drive drops from thousands of points to a few hundred.
     */
    private fun simplify(pts: List<List<Double>>): List<List<Double>> {
        if (pts.size < 3) return pts
        var w = 180.0; var e = -180.0; var s = 90.0; var n = -90.0
        for (p in pts) { w = minOf(w, p[0]); e = maxOf(e, p[0]); s = minOf(s, p[1]); n = maxOf(n, p[1]) }
        val k = cos(Math.toRadians((s + n) / 2))
        val wx = (e - w) * k
        val diag = sqrt(wx * wx + (n - s) * (n - s))
        val eps = (diag * SIMPLIFY_FRACTION).coerceAtLeast(SIMPLIFY_MIN_DEG)
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            val ax = pts[from][0] * k; val ay = pts[from][1]
            val dx = pts[to][0] * k - ax; val dy = pts[to][1] - ay
            val len2 = dx * dx + dy * dy
            var maxD = 0.0
            var maxI = -1
            for (i in from + 1 until to) {
                val px = pts[i][0] * k; val py = pts[i][1]
                val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                val qx = ax + dx * t - px; val qy = ay + dy * t - py
                val d = qx * qx + qy * qy
                if (d > maxD) { maxD = d; maxI = i }
            }
            if (maxI >= 0 && maxD > eps * eps) {
                keep[maxI] = true
                stack.addLast(intArrayOf(from, maxI))
                stack.addLast(intArrayOf(maxI, to))
            }
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    companion object {
        /** Hops at least this long are drawn as flights without asking for a road. */
        const val FLIGHT_KM = 700.0
        private const val MAX_SNAP_M = 20_000.0
        /** Gap between the photo and the road worth drawing as a dashed off-road bit. */
        private const val OFF_ROAD_M = 30.0
        private const val SIMPLIFY_FRACTION = 0.0004
        private const val SIMPLIFY_MIN_DEG = 0.00003
        private const val DETOUR_MIN_KM = 100.0
        private const val MAX_DETOUR = 2.5
        private const val MIN_INTERVAL_MS = 1100L
        private const val FLIGHT_MARK = "flight"
        private const val ARC_POINTS = 96

        /**
         * Great-circle path from 1 to 2, bowed a little to the side (poleward) so it reads as a flight
         * even where the great circle is nearly straight on the map. Longitudes stay continuous.
         */
        fun flightArc(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route {
            val p1 = toVec(lat1, lon1)
            val p2 = toVec(lat2, lon2)
            val dot = (p1[0] * p2[0] + p1[1] * p2[1] + p1[2] * p2[2]).coerceIn(-1.0, 1.0)
            val omega = kotlin.math.acos(dot)
            val raw = ArrayList<DoubleArray>(ARC_POINTS + 1)
            var prevLon = lon1
            for (i in 0..ARC_POINTS) {
                val t = i.toDouble() / ARC_POINTS
                val (lat, lonRaw) = if (omega < 1e-9) {
                    lat1 + (lat2 - lat1) * t to lon1 + (lon2 - lon1) * t
                } else {
                    val a = sin((1 - t) * omega) / sin(omega)
                    val b = sin(t * omega) / sin(omega)
                    val x = a * p1[0] + b * p2[0]
                    val y = a * p1[1] + b * p2[1]
                    val z = a * p1[2] + b * p2[2]
                    Math.toDegrees(asin(z / sqrt(x * x + y * y + z * z))) to Math.toDegrees(atan2(y, x))
                }
                // Unwrap across the antimeridian so the line does not jump around the world.
                var lon = lonRaw
                while (lon - prevLon > 180) lon -= 360
                while (lon - prevLon < -180) lon += 360
                prevLon = lon
                raw.add(doubleArrayOf(lon, lat))
            }
            // Bow: perpendicular offset on a flat (cos-lat scaled) plane, largest mid-way.
            val midLat = Math.toRadians((lat1 + lat2) / 2)
            val k = cos(midLat).coerceAtLeast(0.2)
            val dx = (raw.last()[0] - raw.first()[0]) * k
            val dy = raw.last()[1] - raw.first()[1]
            val len = sqrt(dx * dx + dy * dy)
            val north = lat1 + lat2 >= 0
            val flip = if (north) dx < 0 else dx > 0
            val nx = if (flip) dy else -dy
            val ny = if (flip) -dx else dx
            val pts = raw.mapIndexed { i, p ->
                val t = i.toDouble() / ARC_POINTS
                val off = if (len < 1e-9) 0.0 else BOW * sin(PI * t)
                listOf(p[0] + nx * off / k, (p[1] + ny * off).coerceIn(-85.0, 85.0))
            }
            return Route(pts, true)
        }

        private const val BOW = 0.12

        private fun toVec(lat: Double, lon: Double): DoubleArray {
            val la = Math.toRadians(lat)
            val lo = Math.toRadians(lon)
            return doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
        }
    }
}
