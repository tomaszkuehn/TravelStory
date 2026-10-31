package com.travel.slideshow.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.media.FaceDetector
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the faces are in a photo, for aiming the Ken Burns zoom. Detection runs on the phone
 * (android.media.FaceDetector on a downscaled copy); photos are looked at shortly before they show.
 */
object FaceFocus {
    /** Focus point as fractions of the upright photo's width and height. */
    data class Point(val x: Float, val y: Float)

    private val NONE = Point(-1f, -1f)
    private val results = ConcurrentHashMap<String, Point>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private const val MAX_SIDE = 640
    private const val MAX_FACES = 6
    private const val MAX_CACHE = 500

    fun isDone(uri: Uri): Boolean = results.containsKey(uri.toString())

    fun faceAt(uri: Uri): Point? = results[uri.toString()]?.takeIf { it.x >= 0f }

    fun prefetch(context: Context, uri: Uri) {
        val key = uri.toString()
        if (results.containsKey(key) || !running.add(key)) return
        val app = context.applicationContext
        scope.launch {
            val p = runCatching { detect(app, uri) }.getOrNull()
            if (results.size > MAX_CACHE) results.clear()
            results[key] = p ?: NONE
            running.remove(key)
        }
    }

    private fun detect(context: Context, uri: Uri): Point? {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // The photo is shown upright (EXIF rotation applied), so detect on the upright image too.
        val rotation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        }.getOrDefault(0)
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        // FaceDetector needs RGB_565 and an even width.
        val w = bmp.width and 1.inv()
        if (w < 2) return null
        if (bmp.config != Bitmap.Config.RGB_565 || w != bmp.width) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, w, bmp.height).copy(Bitmap.Config.RGB_565, false) ?: return null
        }
        val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val found = FaceDetector(w, bmp.height, MAX_FACES).findFaces(bmp, faces)
        var sx = 0f
        var sy = 0f
        var sw = 0f
        val mid = PointF()
        for (i in 0 until found) {
            val f = faces[i] ?: continue
            if (f.confidence() < 0.3f) continue
            f.getMidPoint(mid)
            // Bigger faces count more; the midpoint is between the eyes, the face centre a bit lower.
            val weight = f.eyesDistance()
            sx += mid.x * weight
            sy += (mid.y + f.eyesDistance() * 0.4f) * weight
            sw += weight
        }
        if (sw <= 0f) return null
        return Point((sx / sw / w).coerceIn(0f, 1f), (sy / sw / bmp.height).coerceIn(0f, 1f))
    }
}
