package ma.odema.core

import kotlinx.coroutines.runBlocking
import ma.odema.core.providers.OpenMeteoOverview
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Calls the real APIs. Run with: ./gradlew :core:test -Plive --tests '*LiveTest*' */
class LiveTest {
    @Before
    fun onlyWhenAsked() = assumeTrue("set -Plive to call the real APIs", System.getProperty("odema.live") == "1")

    @Test
    fun everySourceAnswersForMorocco() = runBlocking {
        val service = ForecastService()
        val now = System.currentTimeMillis() / 1000
        val succeeded = mutableSetOf<SourceId>()
        for (id in listOf("casablanca", "ifrane", "marrakesh", "dakhla")) {
            val city = MoroccoCities.byId(id)!!
            val place = Place(id, city.fr, city.point, city.id, city.region, city.elevation)
            val r = service.report(place, SourceId.entries.toSet(), now)
            for (s in SourceId.entries) {
                val o = r.outcomes[s]
                val text = when (o) {
                    is SourceOutcome.Success -> { succeeded += s; "${o.samples.size} samples${o.note?.let { ", $it" } ?: ""}" }
                    SourceOutcome.NotCovered -> "not covered"
                    is SourceOutcome.Failure -> "FAILED ${o.message}"
                    null -> "missing"
                }
                println("[live] ${city.fr} ${s.name}: $text")
            }
            val o = RainAnalyzer.outlook(r, AlertSettings(), now)
            println("[live] ${city.fr}: now ${r.current?.temperature}°C code ${r.current?.code}; rain in 2 h: ${o.willRain} (${o.agreeing}/${o.available}); 7 days: ${r.daily.map { it.precipitationSum }}")
        }
        assertEquals(SourceId.entries.toSet(), succeeded, "every source should work somewhere in Morocco")
    }

    @Test
    fun rainHistoryArchive() = runBlocking {
        val client = UrlConnectionHttpClient(timeoutMs = 60_000)
        val point = MoroccoCities.byId("kenitra")!!.point
        val today = java.time.LocalDate.now().toEpochDay()
        val (first, values) = ma.odema.core.farm.RainArchive.fetch(ma.odema.core.farm.HistoryChunk("k", point, today - 400, today - 5), client)
        println("[live] archive last year: from ${java.time.LocalDate.ofEpochDay(first)}, ${values.count { it != null }}/${values.size} days, ${values.filterNotNull().sum().toInt()} mm")
        assertTrue(values.count { it != null } > 380)
        val start1940 = java.time.LocalDate.of(1940, 1, 1).toEpochDay()
        val (_, y1940) = ma.odema.core.farm.RainArchive.fetch(ma.odema.core.farm.HistoryChunk("k", point, start1940, start1940 + 365), client)
        println("[live] archive 1940: ${y1940.count { it != null }} days, ${y1940.filterNotNull().sum().toInt()} mm")
        assertTrue(y1940.count { it != null } > 360)
        val recent = ma.odema.core.farm.RecentRain.fetch(listOf(point, MoroccoCities.byId("fez")!!.point), client)
        println("[live] recent: ${recent.size} points × ${recent[0].precipitation.size} days, next 7 days ${recent.map { r -> r.precipitation.takeLast(7).filterNotNull().sum() }}")
        assertEquals(2, recent.size)
    }

    @Test
    fun overviewAndGeocoder() = runBlocking {
        val capitals = MoroccoRegions.all.map { MoroccoCities.byId(it.capitalId)!! }
        val entries = OpenMeteoOverview.fetch(capitals.map { it.lat to it.lon }, UrlConnectionHttpClient())
        assertEquals(12, entries.size)
        capitals.zip(entries).forEach { (c, e) -> println("[live] overview ${c.fr}: ${e.daily.map { it.precipitationSum }}") }
        val results = MoroccoGeocoder().search("Chefchaouen", "ar")
        println("[live] geocoder: $results")
        assertTrue(results.isNotEmpty())
    }
}
