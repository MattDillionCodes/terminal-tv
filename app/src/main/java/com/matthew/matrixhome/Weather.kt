package com.matthew.matrixhome

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Where the weather is for, as chosen in system/ > weather. [fahrenheit] follows the place's country. */
data class Place(val name: String, val lat: Double, val lon: Double, val fahrenheit: Boolean)

/**
 * Current conditions from Open-Meteo (free, no API key) for the [Place] you set. Printed as words,
 * not icons, so it reads like the rest of the screen: `64°F clear`, `18°C rain`.
 */
class Weather(private val temp: Int, private val code: Int, private val fahrenheit: Boolean) {
    /** WMO weather code, in a word or two. */
    private val words: String
        get() = when (code) {
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "cloudy"
            45, 48 -> "fog"
            in 51..57 -> "drizzle"
            in 61..67 -> "rain"
            in 71..77 -> "snow"
            in 80..82 -> "showers"
            85, 86 -> "snow showers"
            in 95..99 -> "thunderstorm"
            else -> "unknown"
        }

    override fun toString() = "$temp°${if (fahrenheit) "F" else "C"} $words"

    companion object {
        /** The few countries that still use Fahrenheit day to day. */
        private val FAHRENHEIT = setOf("US", "PR", "GU", "VI", "AS", "MP", "LR", "BS", "KY", "BZ", "PW", "FM", "MH")

        /** Blocking: call off the main thread. Throws when offline; the caller keeps the last reading. */
        fun fetch(place: Place): Weather {
            val now = get("https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                "&current=temperature_2m,weather_code&timezone=auto" +
                if (place.fahrenheit) "&temperature_unit=fahrenheit" else "").getJSONObject("current")
            return Weather(now.getDouble("temperature_2m").roundToInt(), now.getInt("weather_code"), place.fahrenheit)
        }

        /** Blocking: the best match for a city name (Open-Meteo geocoding), or null if there's none. Throws when offline. */
        fun find(query: String): Place? {
            val results = get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" +
                URLEncoder.encode(query, "UTF-8")).optJSONArray("results") ?: return null
            val r = results.optJSONObject(0) ?: return null
            val name = listOf(r.optString("name"), r.optString("admin1"), r.optString("country_code"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            return Place(name, r.getDouble("latitude"), r.getDouble("longitude"), r.optString("country_code") in FAHRENHEIT)
        }

        private fun get(url: String): JSONObject {
            val body = (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MatrixHome")
                try { inputStream.use { String(it.readBytes()) } } finally { disconnect() }
            }
            return JSONObject(body)
        }
    }
}
