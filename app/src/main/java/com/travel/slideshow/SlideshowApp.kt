package com.travel.slideshow

import android.app.Application
import org.osmdroid.config.Configuration

class SlideshowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir
            osmdroidTileCache = cacheDir.resolve("tiles")
            // Zoom animations pass through many tile levels; a roomy memory cache keeps the levels
            // already seen (and the ones fetched ahead) ready instead of reloading them mid-zoom.
            cacheMapTileCount = 160
            tileFileSystemMaxQueueSize = 120
            tileDownloadMaxQueueSize = 120
        }
    }
}