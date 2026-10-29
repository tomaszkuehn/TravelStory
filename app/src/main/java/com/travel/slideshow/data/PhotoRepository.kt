package com.travel.slideshow.data

import android.content.Context
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
                    if (!isImage(name, mime)) continue
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
                    val meta = readExif(docUri, sdf)
                    result.add(
                        PhotoItem(
                            uri = docUri,
                            name = name,
                            dateTakenMs = meta.date ?: lastMod.takeIf { it > 0 },
                            lat = meta.lat,
                            lon = meta.lon,
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
        val cmp = compareBy { p: PhotoItem -> p.dateTakenMs ?: Long.MAX_VALUE }
        if (newestFirst) result.sortWith(cmp.reversed()) else result.sortWith(cmp)
        result
    }

    private class ExifMeta(val date: Long?, val lat: Double?, val lon: Double?)

    private fun readExif(uri: Uri, sdf: SimpleDateFormat): ExifMeta {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ExifMeta(null, null, null)
            val ex = ExifInterface(java.io.ByteArrayInputStream(bytes))
            val dateSrc = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
            val date = runCatching {
                dateSrc?.let { if (it.length >= 19) sdf.parse(it.substring(0, 19))?.time else null }
            }.getOrNull()
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
            ExifMeta(date, lat, lon)
        } catch (e: Exception) {
            ExifMeta(null, null, null)
        }
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

    companion object {
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}