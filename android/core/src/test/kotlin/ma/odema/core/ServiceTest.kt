package ma.odema.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServiceTest {
    private fun client(failEverything: Boolean = false) = MockHttp { url ->
        when {
            failEverything -> HttpResponse(503, "")
            "ensemble-api" in url -> HttpResponse(200, Fixtures.text("ensemble_casablanca.json"))
            "api.open-meteo.com" in url && "models=" in url -> HttpResponse(200, Fixtures.text("models_dakhla.json"))
            "api.open-meteo.com" in url -> HttpResponse(200, Fixtures.text("bestmatch_ifrane_synthetic.json"))
            "api.met.no" in url -> HttpResponse(200, Fixtures.text("metnorway_casablanca.json"))
            "brightsky" in url -> HttpResponse(404, Fixtures.text("brightsky_none.json"))
            else -> HttpResponse(500, "")
        }
    }

    @Test
    fun collectsEverySourceInParallel() = runTest {
        val http = client()
        val r = ForecastService(http).report(ifrane, SourceId.entries.toSet(), T0)
        assertEquals(SourceId.entries.toSet(), r.outcomes.keys)
        assertEquals(SourceOutcome.NotCovered, r.outcomes[SourceId.BRIGHT_SKY])
        assertTrue(SourceId.entries.filter { it != SourceId.BRIGHT_SKY }.all { r.outcomes[it] is SourceOutcome.Success })
        assertEquals(5, http.requests.size, "one request per API: ${http.requests}")
        assertEquals(9.4, r.current?.temperature)
        assertEquals(7, r.daily.size)
    }

    @Test
    fun reusesFreshDataAndRefetchesStale() = runTest {
        val http = client()
        val service = ForecastService(http)
        val first = service.report(ifrane, SourceId.entries.toSet(), T0)
        // 20 minutes later: best match (10 min TTL) and MET (30 min) — MET still fresh; ensembles, models, stations fresh too.
        val second = service.report(ifrane, SourceId.entries.toSet(), T0 + 20 * 60, previous = first)
        assertEquals(6, http.requests.size, "only best match is fetched again: ${http.requests}")
        assertEquals(T0 + 20 * 60, second.sourceTimes[SourceId.BEST_MATCH])
        assertEquals(T0, second.sourceTimes[SourceId.ECMWF])
        assertEquals(7, second.daily.size)
        // Forcing a refresh asks everyone again.
        service.report(ifrane, SourceId.entries.toSet(), T0 + 21 * 60, previous = second, forceFresh = true)
        assertEquals(11, http.requests.size)
    }

    @Test
    fun failuresKeepRecentGoodData() = runTest {
        val good = ForecastService(client()).report(ifrane, SourceId.entries.toSet(), T0)
        val bad = ForecastService(client(failEverything = true)).report(ifrane, SourceId.entries.toSet(), T0 + 3 * 3600, previous = good, forceFresh = true)
        assertIs<SourceOutcome.Success>(bad.outcomes[SourceId.ECMWF], "3 h old data is kept")
        val stale = ForecastService(client(failEverything = true)).report(ifrane, SourceId.entries.toSet(), T0 + 7 * 3600, previous = good, forceFresh = true)
        assertIs<SourceOutcome.Failure>(stale.outcomes[SourceId.ECMWF], "7 h old data is dropped")
    }
}
