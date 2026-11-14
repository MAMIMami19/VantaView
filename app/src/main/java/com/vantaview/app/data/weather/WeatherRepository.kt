// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app.data.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** A day in the forecast. Temperatures are already in the chosen unit. */
data class WeatherDay(val date: String, val code: Int, val high: Int, val low: Int)

data class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val days: List<WeatherDay>,
    /** The town's name when the user picked one; null for the device's own location. */
    val place: String?,
    val fahrenheit: Boolean,
    val fetchedAt: Long,
)

/** A town found by name, for choosing where the weather is for. */
data class Place(val name: String, val area: String, val lat: Double, val lon: Double)

/**
 * Weather for Home, from Open-Meteo (free, no key). Uses the device's rough location when allowed,
 * otherwise a town chosen in Settings. Nothing is sent anywhere else.
 */
@Singleton
class WeatherRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val sp = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply(); if (!v) _weather.value = null }

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", false)
        set(v) { sp.edit().putBoolean("fahrenheit", v).apply(); _weather.value = null }

    /** A chosen town, used instead of the device's location. */
    var place: Place?
        get() = sp.getString("place_name", null)?.let {
            Place(it, sp.getString("place_area", "").orEmpty(), sp.getFloat("place_lat", 0f).toDouble(), sp.getFloat("place_lon", 0f).toDouble())
        }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("place_name").remove("place_area").remove("place_lat").remove("place_lon")
            else e.putString("place_name", v.name).putString("place_area", v.area).putFloat("place_lat", v.lat.toFloat()).putFloat("place_lon", v.lon.toFloat())
            e.apply()
            _weather.value = null
        }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Rough device location from Android itself (no Google services needed), or null. */
    @SuppressLint("MissingPermission")
    private fun deviceLocation(): Location? {
        if (!hasLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /** True when there's somewhere to get weather for. */
    fun hasSource(): Boolean = place != null || deviceLocation() != null

    /** Refreshes at most every 30 minutes. */
    suspend fun refresh(force: Boolean = false): Weather? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val cached = _weather.value
        if (!force && cached != null && System.currentTimeMillis() - cached.fetchedAt < 30 * 60_000L) return@withContext cached
        val chosen = place
        val (lat, lon) = chosen?.let { it.lat to it.lon }
            ?: deviceLocation()?.let { it.latitude to it.longitude }
            ?: return@withContext null
        val f = fahrenheit
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&forecast_days=5" + if (f) "&temperature_unit=fahrenheit" else ""
        val w = runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val o = JSONObject(r.body?.string().orEmpty())
                val cur = o.getJSONObject("current")
                val d = o.getJSONObject("daily")
                val dates = d.getJSONArray("time")
                val codes = d.getJSONArray("weather_code")
                val hi = d.getJSONArray("temperature_2m_max")
                val lo = d.getJSONArray("temperature_2m_min")
                val days = (0 until dates.length()).map { i ->
                    WeatherDay(dates.getString(i), codes.optInt(i), Math.round(hi.optDouble(i)).toInt(), Math.round(lo.optDouble(i)).toInt())
                }
                Weather(
                    temp = Math.round(cur.optDouble("temperature_2m")).toInt(),
                    code = cur.optInt("weather_code"),
                    isDay = cur.optInt("is_day", 1) == 1,
                    high = days.firstOrNull()?.high ?: 0,
                    low = days.firstOrNull()?.low ?: 0,
                    days = days,
                    place = chosen?.name,
                    fahrenheit = f,
                    fetchedAt = System.currentTimeMillis(),
                )
            }
        }.getOrNull()
        if (w != null) _weather.value = w
        w ?: cached
    }

    /** Towns matching a name (Open-Meteo's free place search). */
    suspend fun search(name: String): List<Place> = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext emptyList()
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name=" +
            URLEncoder.encode(name.trim(), "UTF-8")
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val a = JSONObject(r.body?.string().orEmpty()).optJSONArray("results") ?: return@use emptyList()
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
                    Place(
                        name = p.optString("name"),
                        area = listOf(p.optString("admin1"), p.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                        lat = p.optDouble("latitude"), lon = p.optDouble("longitude"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        /** WMO weather codes as plain words. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            51, 53, 55, 56, 57 -> "Drizzle"
            61, 63, 65, 66, 67 -> "Rain"
            71, 73, 75, 77 -> "Snow"
            80, 81, 82 -> "Showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunder"
            else -> "—"
        }

        /** Which picture to draw for a code. */
        fun kind(code: Int): String = when (code) {
            0, 1 -> "sun"
            2 -> "partly"
            3 -> "cloud"
            45, 48 -> "fog"
            in 51..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            95, 96, 99 -> "storm"
            else -> "cloud"
        }
    }
}
