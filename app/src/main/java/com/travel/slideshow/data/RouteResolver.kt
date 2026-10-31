package com.travel.slideshow.data

import android.content.Context
import com.travel.slideshow.data.geo.Geo
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

/** A leg between two photos as [lon, lat] points: a road route, or a flight arc. */
data class Route(val points: List<List<Double>>, val flight: Boolean)

/**
 * Road routes from the public OSRM server, cached on disk (one file per leg in `files/routes/`).
 * Long hops, and places OSRM cannot connect by road (islands, other continents), become a flight arc.
 * Respects the demo server policy: one request per second, identifying User-Agent.
 */
class RouteResolver(context: Context) {

    private val dir = File(context.filesDir, "routes")
    private val lock = Mutex()
    private var lastRequestAt = 0L

    /** The leg from 1 to 2; null when OSRM could not be asked (offline, rate limit) — not cached, retried later. */
    suspend fun resolve(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Route? = lock.withLock {
        if (Geo.haversineKm(lat1, lon1, lat2, lon2) >= FLIGHT_KM) return@withLock flightArc(lat1, lon1, lat2, lon2)
        val file = File(dir, fileName(lat1, lon1, lat2, lon2))
        withContext(Dispatchers.IO) { read(file) }?.let { road ->
            return@withLock if (road.isEmpty()) flightArc(lat1, lon1, lat2, lon2) else Route(road, false)
        }
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
        val road = withContext(Dispatchers.IO) { fetch(lat1, lon1, lat2, lon2) } ?: return@withLock null
        withContext(Dispatchers.IO) { write(file, road) }
        if (road.isEmpty()) flightArc(lat1, lon1, lat2, lon2) else Route(road, false)
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
    private fun fetch(lat1: Double, lon1: Double, lat2: Double, lon2: Double): List<List<Double>>? {
        return try {
            val url = java.net.URL(
                String.format(
                    Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
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

    private fun fileName(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String =
        String.format(Locale.US, "%.5f_%.5f_%.5f_%.5f.txt", lat1, lon1, lat2, lon2)

    companion object {
        /** Hops at least this long are drawn as flights without asking for a road. */
        const val FLIGHT_KM = 700.0
        private const val MAX_SNAP_M = 20_000.0
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
