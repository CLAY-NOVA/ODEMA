package ma.odema.core

import java.time.OffsetDateTime

object Fixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()
}

fun t(iso: String): Long = OffsetDateTime.parse(iso).toEpochSecond()

fun sample(start: String, minutes: Long = 60, mm: Double? = null, pct: Double? = null, code: Int? = null, snow: Double? = null) =
    PrecipSample(t(start), minutes * 60, mm, pct, snow, code)

fun report(now: Long, vararg outcomes: Pair<SourceId, SourceOutcome>) = ForecastReport(now, outcomes.toMap())

fun ok(vararg samples: PrecipSample) = SourceOutcome.Success(samples.toList())

/** Serves canned responses and counts requests per host. */
class MockHttp(private val route: (String) -> HttpResponse) : HttpClient {
    val requests = mutableListOf<String>()
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
        synchronized(requests) { requests += url }
        return route(url)
    }
    fun count(host: String) = synchronized(requests) { requests.count { host in it } }
}

val ifrane = Place("p1", "Ifrane", GeoPoint(33.5267, -5.1102), cityId = "ifrane", region = 3, elevation = 1648.0)
const val T0 = 1794312000L // 2026-11-10T12:00Z, "now" for the synthetic Ifrane storm
