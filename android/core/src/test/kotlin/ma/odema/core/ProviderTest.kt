package ma.odema.core

import kotlinx.coroutines.test.runTest
import ma.odema.core.providers.BrightSky
import ma.odema.core.providers.MetNorway
import ma.odema.core.providers.OpenMeteoBestMatch
import ma.odema.core.providers.OpenMeteoEnsemble
import ma.odema.core.providers.OpenMeteoModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Parses responses recorded from the real APIs over Morocco on 2026-11-10. */
class ProviderTest {
    @Test
    fun modelsOverDakhlaSplitPerModel() {
        val all = OpenMeteoModels().sources
        val outcomes = OpenMeteoModels.parse(Fixtures.text("models_dakhla.json"), all)
        assertEquals(all.toSet(), outcomes.keys)
        for (source in all) {
            val samples = assertNotNull(outcomes[source]?.samples, "$source should have data over Morocco")
            assertEquals(48, samples.size)
            assertTrue(samples.all { it.duration == 3600L && it.code != null })
        }
        // Values describe the preceding hour.
        assertEquals(1794308400L - 3600, outcomes.getValue(SourceId.ECMWF).samples!!.first().start)
        // JMA and Météo-France publish amounts but no probability.
        assertTrue(outcomes.getValue(SourceId.JMA).samples!!.all { it.probability == null && it.intensity != null })
        assertTrue(outcomes.getValue(SourceId.ECMWF).samples!!.any { it.probability != null })
    }

    @Test
    fun ensembleBecomesProbability() {
        val outcomes = OpenMeteoEnsemble.parse(Fixtures.text("ensemble_casablanca.json"), OpenMeteoEnsemble().sources)
        val ecmwf = assertIs<SourceOutcome.Success>(outcomes[SourceId.ECMWF_ENS])
        assertEquals("51 members", ecmwf.note)
        assertEquals(6, ecmwf.samples.size)
        // 33 of 51 members give ≥ 0.1 mm in the third hour.
        assertEquals(64.7, ecmwf.samples[2].probability!!, 0.1)
        assertEquals(0.102, ecmwf.samples[2].intensity!!, 0.001)
        assertEquals("31 members", (outcomes[SourceId.GFS_ENS] as SourceOutcome.Success).note)
        assertEquals("40 members", (outcomes[SourceId.ICON_ENS] as SourceOutcome.Success).note)
    }

    @Test
    fun metNorwayHourlyThenSixHourlyWithoutOverlap() {
        val samples = MetNorway.parse(Fixtures.text("metnorway_casablanca.json"))
        assertEquals(t("2026-11-10T11:00:00Z"), samples.first().start)
        assertEquals(3600, samples.first().duration)
        assertTrue(samples.all { it.duration == 3600L || it.duration == 6 * 3600L })
        samples.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
        assertTrue(samples.first().temp != null)
    }

    @Test
    fun brightSkyStationOverMarrakech() {
        val (samples, note) = BrightSky.parse(Fixtures.text("brightsky_marrakech.json"))
        assertEquals(27, samples.size)
        assertEquals(t("2026-11-10T10:00:00Z"), samples.first().start, "values describe the preceding hour")
        assertEquals("MARRAKECH, 5 km", note)
    }

    @Test
    fun brightSkyWithoutStationIsNotCovered() = runTest {
        val client = MockHttp { HttpResponse(404, Fixtures.text("brightsky_none.json")) }
        val result = BrightSky().fetch(ifrane, setOf(SourceId.BRIGHT_SKY), T0, client)
        assertEquals(SourceOutcome.NotCovered, result.outcomes[SourceId.BRIGHT_SKY])
    }

    @Test
    fun rateLimitReasonIsSurfaced() = runTest {
        val client = MockHttp { HttpResponse(429, Fixtures.text("openmeteo_429.json")) }
        val result = OpenMeteoBestMatch().fetch(ifrane, setOf(SourceId.BEST_MATCH), T0, client)
        val failure = assertIs<SourceOutcome.Failure>(result.outcomes[SourceId.BEST_MATCH])
        assertTrue("Rate limited" in failure.message && "Daily API request limit" in failure.message, failure.message)
    }

    @Test
    fun bestMatchFifteenMinutesThenHourlyPlusCurrentAndDaily() {
        val result = OpenMeteoBestMatch.parse(Fixtures.text("bestmatch_ifrane_synthetic.json"))
        val samples = result.outcomes.getValue(SourceId.BEST_MATCH).samples!!
        assertEquals(T0 - 900, samples.first().start)
        val firstWet = samples.first { (it.intensity ?: 0.0) > 0 }
        assertEquals(T0 + 2700, firstWet.start)
        assertEquals(1.2, firstWet.intensity!!, 1e-9)
        assertEquals(85.0, firstWet.probability)
        val fineEnd = samples.filter { it.duration == 900L }.maxOf { it.end }
        assertTrue(samples.filter { it.duration == 3600L }.all { it.start >= fineEnd })
        assertEquals(9.4, result.current?.temperature)
        assertEquals(7, result.daily.size)
        assertEquals(15.5, result.daily.first().precipitationSum)
    }

    @Test
    fun geocoderParsesMoroccanResults() {
        val results = MoroccoGeocoder.parse(Fixtures.text("geocoding_ifrane.json"))
        val first = results.first()
        assertEquals("Ifrane", first.name)
        assertEquals("Fès-Meknès", first.region)
        assertEquals(33.52666, first.point.lat, 1e-6)
        assertEquals(1648.0, first.elevation)
    }

    @Test
    fun urlsUseFourDecimalsElevationAndPlainCommas() {
        val url = OpenMeteoModels().url(ifrane, listOf("ecmwf_ifs", "icon_seamless"))
        assertTrue("latitude=33.5267&longitude=-5.1102&elevation=1648" in url, url)
        assertTrue("models=ecmwf_ifs,icon_seamless" in url, url)
        assertTrue("altitude=1648" in MetNorway().url(ifrane))
    }
}
