package com.travel.slideshow.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Town/city names from Nominatim reverse geocoding, cached on disk.
 * Respects the public instance policy: one request per second, identifying User-Agent.
 */
class PlaceResolver(context: Context) {

    private val file = File(context.filesDir, "places.json")
    private val cache = HashMap<String, String>()
    private val lock = Mutex()
    private var loaded = false
    private var lastRequestAt = 0L

    /** Disk-cached answers for [keys]; value is null for "looked up, no name". Missing keys were never asked. */
    suspend fun cachedMany(keys: Collection<String>): Map<String, String?> = lock.withLock {
        ensureLoaded()
        keys.mapNotNull { k -> cache[k]?.let { k to it.ifEmpty { null } } }.toMap()
    }

    /** Cached name, or a network lookup; null when offline or nothing found (not cached, retried next run). */
    suspend fun resolve(lat: Double, lon: Double): String? = lock.withLock {
        ensureLoaded()
        val k = key(lat, lon)
        cache[k]?.let { return@withLock it.ifEmpty { null } }
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        lastRequestAt = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { fetch(lat, lon) } ?: return@withLock null
        // An empty string marks "no name here" so the spot is not asked about again.
        cache[k] = result
        withContext(Dispatchers.IO) { save() }
        result.ifEmpty { null }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        loaded = true
        withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) return@runCatching
                val obj = JSONObject(file.readText())
                for (k in obj.keys()) cache[k] = obj.getString(k)
            }
        }
    }

    private fun save() {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in cache) obj.put(k, v)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(file)
        }
    }

    private fun fetch(lat: Double, lon: Double): String? {
        return try {
            val url = java.net.URL(
                String.format(
                    Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.5f&lon=%.5f&zoom=10&accept-language=pl",
                    lat, lon,
                )
            )
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TravelSlideshow/1.0 (Android; personal photo slideshow)")
            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            conn.disconnect()
            val root = JSONObject(body)
            if (root.has("error")) return ""
            val addr = root.optJSONObject("address")
            val name = PLACE_KEYS.firstNotNullOfOrNull { k -> addr?.optString(k)?.takeIf { it.isNotBlank() } }
                ?: root.optString("name").takeIf { it.isNotBlank() }
            name ?: ""
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** ~1 km grid: photos taken around the same town share one lookup. */
        fun key(lat: Double, lon: Double): String = String.format(Locale.US, "%.2f,%.2f", lat, lon)

        private const val MIN_INTERVAL_MS = 1100L
        private val PLACE_KEYS = listOf(
            "city", "town", "village", "hamlet", "municipality", "island", "suburb", "county", "state",
        )
    }
}
