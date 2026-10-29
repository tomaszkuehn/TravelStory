package com.travel.slideshow.data.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val R = 6371.0

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }

    fun pointInRing(lat: Double, lon: Double, ring: List<List<Double>>): Boolean {
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val lonI = ring[i][0]
            val latI = ring[i][1]
            val lonJ = ring[j][0]
            val latJ = ring[j][1]
            if (((latI > lat) != (latJ > lat)) &&
                lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}