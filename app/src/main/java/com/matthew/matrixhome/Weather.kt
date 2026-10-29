package com.matthew.matrixhome

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Current conditions from Open-Meteo (free, no API key), for the same place Glass uses. Printed as
 * words, not icons, so it reads like the rest of the screen: `64°F clear`.
 */
class Weather(private val tempF: Int, private val code: Int) {
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

    override fun toString() = "$tempF°F $words"

    companion object {
        // Los Angeles, CA (as in Glass)
        private const val URL = "https://api.open-meteo.com/v1/forecast?latitude=34.0522&longitude=-118.2437" +
            "&current=temperature_2m,weather_code&temperature_unit=fahrenheit&timezone=auto"

        /** Blocking: call off the main thread. Throws when offline; the caller keeps the last reading. */
        fun fetch(): Weather {
            val body = (URL(URL).openConnection() as HttpURLConnection).run {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "MatrixHome/0.1")
                try { inputStream.use { String(it.readBytes()) } } finally { disconnect() }
            }
            val now = JSONObject(body).getJSONObject("current")
            return Weather(now.getDouble("temperature_2m").roundToInt(), now.getInt("weather_code"))
        }
    }
}
