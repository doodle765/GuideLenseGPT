package com.guidelens.app

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val net = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(net) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

object Geo {

    // ---------- geometry ----------
    fun hav(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val t = Math.PI / 180.0
        val dla = (b.lat - a.lat) * t
        val dlo = (b.lng - a.lng) * t
        val h = sin(dla / 2).pow(2) + cos(a.lat * t) * cos(b.lat * t) * sin(dlo / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }

    fun decodePolyline(str: String): List<LatLng> {
        val pts = mutableListOf<LatLng>()
        var i = 0
        var la = 0
        var ln = 0
        while (i < str.length) {
            var b: Int
            var s = 0
            var r = 0
            do { b = str[i++].code - 63; r = r or ((b and 31) shl s); s += 5 } while (b >= 32)
            la += if (r and 1 == 1) (r shr 1).inv() else (r shr 1)
            s = 0; r = 0
            do { b = str[i++].code - 63; r = r or ((b and 31) shl s); s += 5 } while (b >= 32)
            ln += if (r and 1 == 1) (r shr 1).inv() else (r shr 1)
            pts.add(LatLng(la / 1e5, ln / 1e5))
        }
        return pts
    }

    fun routeLen(coords: List<LatLng>): Double {
        var d = 0.0
        for (i in 1 until coords.size) d += hav(coords[i - 1], coords[i])
        return d
    }

    fun nearestDistToRoute(p: LatLng, coords: List<LatLng>): Double {
        var m = Double.MAX_VALUE
        for (c in coords) { val d = hav(p, c); if (d < m) m = d }
        return m
    }

    // ---------- routing model ----------
    data class Step(val type: String, val modifier: String, val name: String, val lat: Double, val lng: Double)

    fun maneuverText(st: Step): String {
        val m = st.type
        val mod = st.modifier
        val onto = if (st.name.isNotEmpty()) " on " + st.name else ""
        if (m.contains("depart")) return "Start walking" + onto
        if (m.contains("roundabout")) return "Take the roundabout"
        if (m.contains("arrive")) return "You are arriving"
        if (m.startsWith("turn") || m.startsWith("new name")) {
            return when (mod) {
                "left" -> "Turn left" + onto
                "right" -> "Turn right" + onto
                "slight left" -> "Bear slight left"
                "slight right" -> "Bear slight right"
                "sharp left" -> "Turn sharp left"
                "sharp right" -> "Turn sharp right"
                "uturn" -> "Make a U-turn"
                else -> "Continue straight" + onto
            }
        }
        return "Continue straight" + onto
    }

    data class Destination(val lat: Double, val lng: Double, val label: String)

    class Route(val coords: List<LatLng>, val steps: List<Step>, val dest: Destination) {
        var stepIdx = 0
        var announcedPre = false
        var announcedNow = false
        var lastRecalc = 0L
    }

    // ---------- network (internet required) ----------
    suspend fun geocode(query: String): Destination? = withContext(Dispatchers.IO) {
        try {
            val u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=en&q=" +
                    URLEncoder.encode(query, "UTF-8")
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "GuideLens/1.0")
            c.connectTimeout = 12000
            c.readTimeout = 12000
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            c.disconnect()
            if (arr.length() == 0) null
            else {
                val o = arr.getJSONObject(0)
                Destination(o.getDouble("lat"), o.getDouble("lon"),
                    o.getString("display_name").split(",")[0].trim())
            }
        } catch (e: Exception) { null }
    }

    suspend fun fetchRoute(from: LatLng, to: Destination): Route? = withContext(Dispatchers.IO) {
        try {
            val u = "https://routing.openstreetmap.de/routed-foot/route/v1/foot/" +
                    "${from.lng},${from.lat};${to.lng},${to.lat}?overview=full&steps=true&geometries=polyline"
            val c = URL(u).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", "GuideLens/1.0")
            c.connectTimeout = 15000
            c.readTimeout = 15000
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            c.disconnect()
            val r = j.getJSONArray("routes").getJSONObject(0)
            val steps = mutableListOf<Step>()
            val legs = r.getJSONArray("legs")
            for (li in 0 until legs.length()) {
                val arr = legs.getJSONObject(li).getJSONArray("steps")
                for (si in 0 until arr.length()) {
                    val st = arr.getJSONObject(si)
                    val man = st.getJSONObject("maneuver")
                    val loc = man.getJSONArray("location")
                    steps.add(Step(
                        type = man.optString("type", ""),
                        modifier = man.optString("modifier", ""),
                        name = st.optString("name", ""),
                        lat = loc.getDouble(1),
                        lng = loc.getDouble(0)
                    ))
                }
            }
            Route(decodePolyline(r.getString("geometry")), steps, to)
        } catch (e: Exception) { null }
    }

    /**
     * Reverse-geocode a fix into a spoken address.
     * Returns:
     *  "NO_INTERNET" -> caller must speak the offline message,
     *  "COORDS"      -> caller speaks raw coordinates,
     *  otherwise     -> the spoken address text.
     */
    fun whereAmIText(context: Context, p: Location): String {
        if (!isOnline(context)) return "NO_INTERNET"
        return try {
            @Suppress("DEPRECATION")
            val addrs: List<Address>? = Geocoder(context, Locale.ENGLISH)
                .getFromLocation(p.latitude, p.longitude, 1)
            if (!addrs.isNullOrEmpty()) {
                val a = addrs[0]
                val parts = listOfNotNull(
                    a.subThoroughfare,
                    a.thoroughfare,
                    a.subLocality,
                    a.locality
                ).filter { it.isNotBlank() }
                if (parts.isEmpty()) "COORDS" else parts.joinToString(", ")
            } else "COORDS"
        } catch (e: IOException) {
            if (!isOnline(context)) "NO_INTERNET" else "COORDS"
        } catch (e: Exception) {
            "COORDS"
        }
    }
}
