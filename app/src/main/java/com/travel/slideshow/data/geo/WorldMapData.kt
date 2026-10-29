package com.travel.slideshow.data.geo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

class GeoBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val area: Double get() = max(0.0, east - west) * max(0.0, north - south)
}

class CountryShape(
    val iso3: String,
    val name: String,
    val iso2: String,
    val outerRings: List<List<List<Double>>>,
    val bbox: GeoBox,
) {
    val mainPolyCentroid: Pair<Double, Double> by lazy {
        var bestRing: List<List<Double>>? = null
        var bestArea = -1.0
        for (ring in outerRings) {
            if (ring.size < 3) continue
            var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
            for (pt in ring) { w = min(w, pt[0]); s = min(s, pt[1]); e = max(e, pt[0]); n = max(n, pt[1]) }
            val a = (e - w) * (n - s)
            if (a > bestArea) { bestArea = a; bestRing = ring }
        }
        val ring = bestRing ?: return@lazy 0.0 to 0.0
        var sl = 0.0; var sc = 0.0
        for (pt in ring) { sl += pt[0]; sc += pt[1] }
        (sc / ring.size) to (sl / ring.size)
    }
}

object CountryInfo {
    private val iso3To2 = mapOf(
        "AFG" to "AF", "AGO" to "AO", "ALB" to "AL", "ARE" to "AE", "ARG" to "AR", "ARM" to "AM",
        "ATA" to "AQ", "ATF" to "TF", "AUS" to "AU", "AUT" to "AT", "AZE" to "AZ", "BDI" to "BI",
        "BEL" to "BE", "BEN" to "BJ", "BFA" to "BF", "BGD" to "BD", "BGR" to "BG", "BHS" to "BS",
        "BIH" to "BA", "BLR" to "BY", "BLZ" to "BZ", "BMU" to "BM", "BOL" to "BO", "BRA" to "BR",
        "BRN" to "BN", "BTN" to "BT", "BWA" to "BW", "CAF" to "CF", "CAN" to "CA", "CHE" to "CH",
        "CHL" to "CL", "CHN" to "CN", "CIV" to "CI", "CMR" to "CM", "COD" to "CD", "COG" to "CG",
        "COL" to "CO", "CRI" to "CR", "CUB" to "CU", "CS-KM" to "XK", "CYP" to "CY", "CZE" to "CZ",
        "DEU" to "DE", "DJI" to "DJ", "DNK" to "DK", "DOM" to "DO", "DZA" to "DZ", "ECU" to "EC",
        "EGY" to "EG", "ERI" to "ER", "ESP" to "ES", "EST" to "EE", "ETH" to "ET", "FIN" to "FI",
        "FJI" to "FJ", "FLK" to "FK", "FRA" to "FR", "GAB" to "GA", "GBR" to "GB", "GEO" to "GE",
        "GHA" to "GH", "GIN" to "GN", "GMB" to "GM", "GNB" to "GW", "GNQ" to "GQ", "GRC" to "GR",
        "GRL" to "GL", "GTM" to "GT", "GUF" to "GF", "GUY" to "GY", "HND" to "HN", "HRV" to "HR",
        "HTI" to "HT", "HUN" to "HU", "IDN" to "ID", "IND" to "IN", "IRL" to "IE", "IRN" to "IR",
        "IRQ" to "IQ", "ISL" to "IS", "ISR" to "IL", "ITA" to "IT", "JAM" to "JM", "JOR" to "JO",
        "JPN" to "JP", "KAZ" to "KZ", "KEN" to "KE", "KGZ" to "KG", "KHM" to "KH", "KOR" to "KR",
        "KWT" to "KW", "LAO" to "LA", "LBN" to "LB", "LBR" to "LR", "LBY" to "LY", "LKA" to "LK",
        "LSO" to "LS", "LTU" to "LT", "LUX" to "LU", "LVA" to "LV", "MAR" to "MA", "MDA" to "MD",
        "MDG" to "MG", "MEX" to "MX", "MKD" to "MK", "MLI" to "ML", "MLT" to "MT", "MMR" to "MM",
        "MNE" to "ME", "MNG" to "MN", "MOZ" to "MZ", "MRT" to "MR", "MWI" to "MW", "MYS" to "MY",
        "NAM" to "NA", "NCL" to "NC", "NER" to "NE", "NGA" to "NG", "NIC" to "NI", "NLD" to "NL",
        "NOR" to "NO", "NPL" to "NP", "NZL" to "NZ", "OMN" to "OM", "PAK" to "PK", "PAN" to "PA",
        "PER" to "PE", "PHL" to "PH", "PNG" to "PG", "POL" to "PL", "PRI" to "PR", "PRK" to "KP",
        "PRT" to "PT", "PRY" to "PY", "PSE" to "PS", "QAT" to "QA", "ROU" to "RO", "RUS" to "RU",
        "RWA" to "RW", "ESH" to "EH", "SAU" to "SA", "SDN" to "SD", "SSD" to "SS", "SEN" to "SN",
        "SLB" to "SB", "SLE" to "SL", "SLV" to "SV", "SOM" to "SO", "SRB" to "RS", "SUR" to "SR",
        "SVK" to "SK", "SVN" to "SI", "SWE" to "SE", "SWZ" to "SZ", "SYR" to "SY", "TCD" to "TD",
        "TGO" to "TG", "THA" to "TH", "TJK" to "TJ", "TKM" to "TM", "TLS" to "TL", "TTO" to "TT",
        "TUN" to "TN", "TUR" to "TR", "TWN" to "TW", "TZA" to "TZ", "UGA" to "UG", "UKR" to "UA",
        "URY" to "UY", "USA" to "US", "UZB" to "UZ", "VEN" to "VE", "VNM" to "VN", "VUT" to "VU",
        "YEM" to "YE", "ZAF" to "ZA", "ZMB" to "ZW", "ZWE" to "ZW",
    )

    fun iso2(iso3: String): String = iso3To2[iso3] ?: ""

    fun flagEmoji(iso3: String): String {
        val iso2 = iso2(iso3)
        if (iso2.length != 2) return "🏳"
        return String(Character.toChars(0x1F1E6 + (iso2[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (iso2[1] - 'A')))
    }
}

object WorldMapData {
    private var shapes: List<CountryShape> = emptyList()
    private var byIso3: Map<String, CountryShape> = emptyMap()

    suspend fun load(context: Context, geojsonAsset: String = "world/countries.geo.json") =
        withContext(Dispatchers.IO) {
            if (shapes.isNotEmpty()) return@withContext
            val text = readAsset(context, geojsonAsset)
            val root = JSONObject(text)
            val list = mutableListOf<CountryShape>()
            val features = root.getJSONArray("features")
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val id = if (f.has("id") && !f.isNull("id")) f.getString("id") else "-99"
                val name = f.getJSONObject("properties").optString("name", id)
                val geo = f.getJSONObject("geometry")
                val type = geo.optString("type")
                if (type != "Polygon" && type != "MultiPolygon") continue
                val rings = mutableListOf<List<List<Double>>>()
                if (type == "Polygon") {
                    rings.add(outerRingPoints(geo.getJSONArray("coordinates")))
                } else {
                    val polys = geo.getJSONArray("coordinates")
                    for (p in 0 until polys.length()) {
                        val ring = outerRingPoints(polys.getJSONArray(p))
                        if (ring.isNotEmpty()) rings.add(ring)
                    }
                }
                if (rings.isEmpty()) continue
                var w = 180.0; var s = 90.0; var e = -180.0; var n = -90.0
                for (ring in rings) for (pt in ring) {
                    w = min(w, pt[0]); s = min(s, pt[1]); e = max(e, pt[0]); n = max(n, pt[1])
                }
                val resolvedIso3 = if (id == "-99") "NM-$name" else id
                val iso2 = CountryInfo.iso2(id)
                val shape = CountryShape(
                    iso3 = resolvedIso3,
                    name = name,
                    iso2 = if (iso2.isNotEmpty()) iso2 else "nm_${name.lowercase()}",
                    outerRings = rings,
                    bbox = GeoBox(w, s, e, n),
                )
                list.add(shape)
            }
            shapes = list
            byIso3 = list.associateBy { it.iso3 }
        }

    private fun readAsset(context: Context, path: String): String {
        context.assets.open(path).use { input ->
            return input.readBytes().decodeToString()
        }
    }

    private fun outerRingPoints(arr: org.json.JSONArray): List<List<Double>> {
        val outer = arr.getJSONArray(0)
        val ring = mutableListOf<List<Double>>()
        for (i in 0 until outer.length()) {
            val pt = outer.getJSONArray(i)
            ring.add(listOf(pt.getDouble(0), pt.getDouble(1)))
        }
        return ring
    }

    fun all(): List<CountryShape> = shapes

    fun byIso(iso3: String): CountryShape? = byIso3[iso3]

    fun isoAt(lat: Double, lon: Double): CountryShape? {
        var best: CountryShape? = null
        var bestArea = Double.MAX_VALUE
        for (c in shapes) {
            if (lon < c.bbox.west - 2 || lon > c.bbox.east + 2 ||
                lat < c.bbox.south - 2 || lat > c.bbox.north + 2
            ) continue
            var hit = false
            for (ring in c.outerRings) {
                if (Geo.pointInRing(lat, lon, ring)) { hit = true; break }
            }
            if (hit) {
                val a = c.bbox.area
                if (a < bestArea) { bestArea = a; best = c }
            }
        }
        return best
    }
}