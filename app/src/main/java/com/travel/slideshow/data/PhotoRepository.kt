package com.travel.slideshow.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import com.travel.slideshow.data.geo.WorldMapData
import com.travel.slideshow.model.PhotoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class PhotoRepository(private val context: Context) {

    suspend fun scan(
        rootUri: Uri,
        newestFirst: Boolean,
        shouldStop: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): List<PhotoItem> = withContext(Dispatchers.IO) {
        val treeId = DocumentsContract.getTreeDocumentId(rootUri)
        val result = ArrayList<PhotoItem>(256)
        val stack = ArrayDeque<String>()
        stack.addLast(treeId)
        val sdf = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        var stoppedNow = false
        while (stack.isNotEmpty()) {
            if (shouldStop()) break
            val dirId = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, dirId)
            runCatching {
                context.contentResolver.query(childrenUri, PROJECTION, null, null, null)
            }.getOrNull()?.use { c ->
                while (c.moveToNext()) {
                    if (shouldStop()) {
                        stoppedNow = true
                        break
                    }
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val lastMod = c.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        stack.addLast(docId)
                        continue
                    }
                    val video = isVideo(name, mime)
                    if (!video && !isImage(name, mime)) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    val meta = if (video) readVideoMeta(docUri) else readExif(docUri, sdf)
                    val fallback = lastMod.takeIf { it > 0 }
                    result.add(
                        PhotoItem(
                            uri = docUri,
                            name = name,
                            dateTakenMs = meta.instant ?: fallback,
                            lat = meta.lat,
                            lon = meta.lon,
                            localTimeMs = meta.local ?: fallback?.let { it + localOffset(it) },
                            isVideo = video,
                            durationMs = meta.durationMs,
                        )
                    )
                    onProgress(result.size)
                }
            }
            if (stoppedNow) break
        }
        for (p in result) {
            if (p.lat != null && p.lon != null) {
                val shape = WorldMapData.isoAt(p.lat, p.lon)
                if (shape != null) {
                    p.iso3 = shape.iso3
                    p.countryName = shape.name
                }
            }
        }
        sortByDate(result, newestFirst)
    }

    private class MediaMeta(
        val instant: Long?,
        val local: Long?,
        val lat: Double?,
        val lon: Double?,
        val durationMs: Long? = null,
    )

    private val noMeta = MediaMeta(null, null, null, null)

    /** Offset of the device time zone; best guess when a file carries no zone of its own. */
    private fun localOffset(ms: Long): Long = TimeZone.getDefault().getOffset(ms).toLong()

    private fun readVideoMeta(uri: Uri): MediaMeta {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            // ISO-6709, e.g. "+52.2297+021.0122/"
            val loc = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val m = loc?.let { Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) }
            var lat = m?.groupValues?.get(1)?.toDoubleOrNull()
            var lon = m?.groupValues?.get(2)?.toDoubleOrNull()
            if (lat == 0.0 && lon == 0.0) { lat = null; lon = null }
            // e.g. "20231015T143000.000Z" (UTC)
            val instant = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.takeIf { it.length >= 15 && !it.startsWith("1904") }
                ?.let { raw ->
                    runCatching {
                        SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .parse(raw.substring(0, 15))?.time
                    }.getOrNull()
                }
            MediaMeta(instant, instant?.let { it + localOffset(it) }, lat, lon, duration)
        } catch (e: Exception) {
            noMeta
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun readExif(uri: Uri, sdf: SimpleDateFormat): MediaMeta {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return noMeta
            val ex = ExifInterface(java.io.ByteArrayInputStream(bytes))
            val dateSrc = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            // EXIF date is wall-clock time; parsing it as UTC keeps the local calendar day.
            val local = runCatching {
                dateSrc?.let { if (it.length >= 19) sdf.parse(it.substring(0, 19))?.time else null }
            }.getOrNull()
            val offsetMs = ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                ?.let { parseOffsetMs(it) }
            val instant = local?.let { it - (offsetMs ?: localOffset(it)) }
            val latRef = ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
            val lonRef = ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF)
            var lat: Double? = null
            var lon: Double? = null
            if (!latRef.isNullOrEmpty() && !lonRef.isNullOrEmpty()) {
                ex.latLong?.let { ll ->
                    lat = ll.getOrNull(0)
                    lon = ll.getOrNull(1)
                }
            }
            if (lat == null || lon == null) {
                manualGps(ex, latRef, lonRef)?.let { (a, b) -> lat = a; lon = b }
            }
            if (lat == null || lon == null) {
                xmpGps(bytes)?.let { (a, b) -> lat = a; lon = b }
            }
            if (lat != null && lon != null && lat == 0.0 && lon == 0.0) {
                lat = null
                lon = null
            }
            MediaMeta(instant, local, lat, lon)
        } catch (e: Exception) {
            noMeta
        }
    }

    /** "+02:00" / "-0530" → milliseconds. */
    private fun parseOffsetMs(raw: String): Long? {
        val m = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(raw.trim()) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val h = m.groupValues[2].toLong()
        val min = m.groupValues[3].toLong()
        return sign * (h * 3_600_000 + min * 60_000)
    }

    private fun manualGps(ex: ExifInterface, latRef: String?, lonRef: String?): Pair<Double, Double>? {
        val latRaw = ex.getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: return null
        val lonRaw = ex.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: return null
        val lat = parseDms(latRaw, latRef) ?: return null
        val lon = parseDms(lonRaw, lonRef) ?: return null
        return lat to lon
    }

    private fun parseDms(raw: String, ref: String?): Double? {
        val parts = raw.split(',')
        if (parts.isEmpty()) return null
        var v = 0.0
        for ((i, part) in parts.withIndex()) {
            val p = part.trim()
            val frac = Regex("(-?[0-9.]+)/([0-9.]+)").findAll(p).firstOrNull()
            val num = if (frac != null) {
                val a = frac.groupValues[1].toDoubleOrNull() ?: return null
                val b = frac.groupValues[2].toDoubleOrNull() ?: return null
                if (b == 0.0) return null
                a / b
            } else {
                p.toDoubleOrNull() ?: return null
            }
            v += num / Math.pow(60.0, i.toDouble())
        }
        val r = ref?.trim()?.uppercase()?.firstOrNull() ?: return null
        if (r == 'S' || r == 'W') v = -v
        else if (r != 'N' && r != 'E') return null
        return v
    }

    private fun xmpGps(bytes: ByteArray): Pair<Double, Double>? {
        val probe = String(bytes, 0, minOf(bytes.size, 1_048_576), Charsets.ISO_8859_1)
        val latM = Regex("""exif:GPSLatitude="([^"]+)"""").find(probe) ?: return null
        val lonM = Regex("""exif:GPSLongitude="([^"]+)"""").find(probe) ?: return null
        val lat = parseXmpCoord(latM.groupValues[1], 'N', 'S') ?: return null
        val lon = parseXmpCoord(lonM.groupValues[1], 'E', 'W') ?: return null
        return lat to lon
    }

    private fun parseXmpCoord(raw: String, posRef: Char, negRef: Char): Double? {
        val s = raw.trim()
        val hemi = when {
            s.startsWith(posRef, ignoreCase = true) || s.endsWith(posRef, ignoreCase = true) -> 1.0
            s.startsWith(negRef, ignoreCase = true) || s.endsWith(negRef, ignoreCase = true) -> -1.0
            else -> 1.0
        }
        val cleaned = s.removeSurrounding("\"")
            .replace(posRef.toString(), "", ignoreCase = true)
            .replace(negRef.toString(), "", ignoreCase = true)
        val nums = Regex("-?[0-9]+(?:\\.[0-9]+)?").findAll(cleaned)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (nums.isEmpty()) return null
        val deg = kotlin.math.abs(nums[0]) +
            (if (nums.size > 1) kotlin.math.abs(nums[1]) / 60.0 else 0.0) +
            (if (nums.size > 2) kotlin.math.abs(nums[2]) / 3600.0 else 0.0)
        return hemi * deg
    }

    private fun isImage(name: String, mime: String): Boolean {
        if (mime.startsWith("image/")) return true
        val low = name.lowercase()
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") ||
            low.endsWith(".webp") || low.endsWith(".heic") || low.endsWith(".heif")
    }

    private fun isVideo(name: String, mime: String): Boolean {
        if (mime.startsWith("video/")) return true
        val low = name.lowercase()
        return VIDEO_EXT.any { low.endsWith(it) }
    }

    companion object {
        fun sortByDate(items: List<PhotoItem>, newestFirst: Boolean): List<PhotoItem> {
            val cmp = compareBy { p: PhotoItem -> p.dateTakenMs ?: Long.MAX_VALUE }
            return items.sortedWith(if (newestFirst) cmp.reversed() else cmp)
        }

        private val VIDEO_EXT = listOf(".mp4", ".mov", ".3gp", ".mkv", ".webm", ".m4v")

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}