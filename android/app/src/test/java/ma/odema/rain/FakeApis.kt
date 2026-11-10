package ma.odema.rain

import ma.odema.core.ForecastReport
import ma.odema.core.HttpClient
import ma.odema.core.HttpResponse
import ma.odema.core.PrecipSample
import ma.odema.core.SourceId
import ma.odema.core.SourceOutcome
import ma.odema.core.providers.OpenMeteoBestMatch

/**
 * Forecasts in Open-Meteo's exact JSON format, built relative to "now" so tests
 * never go stale: an Atlas storm with rain from +45 min, a 10 mm/h thunderstorm
 * hour at +2 h, and snow overnight.
 */
object FakeApis {
    fun bestMatch(now: Long, storm: Boolean = true): String {
        val t0 = (now / 900) * 900
        val minutes = (0..96).map { t0 + 900L * it }
        fun precip(t: Long): Double {
            if (!storm) return 0.0
            val s = t - 900 - t0
            return when {
                s in 2700 until 7200 -> 0.3
                s in 7200 until 10800 -> 2.5
                s in 10800 until 18000 -> 0.5
                else -> 0.0
            }
        }
        fun snow(t: Long): Double = if (storm && (t - 900 - t0) in 18 * 3600L until 22 * 3600L) 0.4 else 0.0
        val h0 = (now / 3600) * 3600
        val hours = (0..169).map { h0 + 3600L * it }
        fun hourSum(f: (Long) -> Double, t: Long) = (1..4).sumOf { k -> f(t - 3600 + 900L * k) }
        val hp = hours.map { hourSum(::precip, it) }
        val hs = hours.map { hourSum(::snow, it) }
        val codes = hours.mapIndexed { i, t ->
            val p = hp[i]
            when {
                storm && (t - 3600 - t0) in 7200 until 10800 -> 95
                hs[i] > 0 -> 73
                p >= 2.5 -> 63
                p > 0 -> 61
                else -> 2
            }
        }
        val probs = hp.mapIndexed { i, p -> if (p > 0 || hs[i] > 0) 85 else 10 }
        val temps = hours.mapIndexed { i, _ -> if (hs[i] > 0) 0.5 else 11.0 - (i % 24) * 0.2 }
        val days = (0..6).map { ((now / 86400) * 86400) - 3600 + 86400L * it }
        fun list(values: List<Any>) = values.joinToString(",", "[", "]")
        return """
        {"latitude":33.52,"longitude":-5.11,"timezone":"Africa/Casablanca","utc_offset_seconds":3600,"elevation":1648.0,
         "current":{"time":$now,"interval":900,"temperature_2m":9.4,"precipitation":0.0,"weather_code":3,"is_day":1},
         "minutely_15":{"time":${list(minutes)},"precipitation":${list(minutes.map(::precip))},"snowfall":${list(minutes.map(::snow))}},
         "hourly":{"time":${list(hours)},"precipitation":${list(hp)},"precipitation_probability":${list(probs)},"weather_code":${list(codes)},"snowfall":${list(hs)},"temperature_2m":${list(temps)}},
         "daily":{"time":${list(days)},"weather_code":[95,3,61,63,3,0,0],"temperature_2m_max":[11.2,9.0,8.5,7.9,10.1,12.3,13.0],
           "temperature_2m_min":[1.0,-0.5,0.2,-1.1,0.5,2.0,3.1],"precipitation_sum":[${if (storm) 15.5 else 0.0},0.0,6.5,9.0,0.0,0.0,0.0],
           "precipitation_probability_max":[90,20,70,80,10,5,5],"precipitation_hours":[5,0,4,6,0,0,0],"snowfall_sum":[${if (storm) 6.4 else 0.0},0,0,1.2,0,0,0]}}
        """.trimIndent()
    }

    /** Serves the storm for best match; every other API is "down", which the app must survive. */
    fun client(now: Long, storm: Boolean = true) = HttpClient { url, _ ->
        if ("api.open-meteo.com" in url && "models=" !in url) HttpResponse(200, bestMatch(now, storm))
        else HttpResponse(503, "")
    }

    /** A full report with every source, for screenshots: [rainIn] seconds until rain (null = dry). */
    fun report(now: Long, rainIn: Long?, storm: Boolean = false, temp: Double = 21.0): ForecastReport {
        val best = OpenMeteoBestMatch.parse(bestMatch(now, storm))
        val outcomes = mutableMapOf<SourceId, SourceOutcome>()
        if (storm) {
            outcomes[SourceId.BEST_MATCH] = best.outcomes.getValue(SourceId.BEST_MATCH)
        }
        val h0 = (now / 3600) * 3600
        SourceId.entries.filter { storm.not() || it != SourceId.BEST_MATCH }.forEachIndexed { i, source ->
            if (source == SourceId.BRIGHT_SKY && rainIn == null) {
                outcomes[source] = SourceOutcome.NotCovered
                return@forEachIndexed
            }
            // Most sources agree; a few disagree, as in real life.
            val agrees = rainIn != null && i % 4 != 3
            val start = if (rainIn != null) now + rainIn + (i % 3) * 900L else Long.MAX_VALUE
            val samples = (0 until 72).map { k ->
                val t = h0 + 3600L * k
                val wet = agrees && t + 3600 > start && t < start + 4 * 3600
                PrecipSample(
                    start = t, duration = 3600,
                    intensity = if (wet) 1.2 + (i % 5) * 0.4 + if (storm) 6.0 else 0.0 else 0.0,
                    probability = if (source.kind == ma.odema.core.SourceKind.ENSEMBLE || i % 2 == 0) (if (wet) 70.0 + i else 8.0) else null,
                    code = if (wet) (if (storm) 95 else 61) else 2,
                    temp = temp - (k % 24) * 0.3,
                )
            }
            outcomes[source] = SourceOutcome.Success(samples, note = when (source.kind) {
                ma.odema.core.SourceKind.ENSEMBLE -> "51 members"
                ma.odema.core.SourceKind.STATION -> "CASABLANCA, 6 km"
                else -> null
            })
        }
        return ForecastReport(
            fetchedAt = now - 240,
            outcomes = outcomes,
            sourceTimes = outcomes.keys.associateWith { now - 240 },
            current = best.current?.copy(temperature = temp, code = if (rainIn != null && rainIn <= 0) 61 else 2),
            daily = best.daily,
        )
    }
}
