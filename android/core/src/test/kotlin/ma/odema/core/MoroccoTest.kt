package ma.odema.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MoroccoTest {
    @Test
    fun citiesAreConsistent() {
        val all = MoroccoCities.all
        assertEquals(108, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size, "ids are unique")
        assertTrue(all.all { MoroccoBounds.contains(it.point) }, "every town is inside the Morocco box")
        for (region in MoroccoRegions.all) {
            assertTrue(MoroccoCities.inRegion(region.number).isNotEmpty(), "region ${region.number} has towns")
            assertNotNull(MoroccoCities.byId(region.capitalId), "capital of ${region.fr}")
        }
        assertTrue(MoroccoCities.byId("oukaimeden")!!.elevation > 2500)
    }

    @Test
    fun searchInThreeLanguagesIgnoringAccents() {
        assertEquals("fez", MoroccoCities.search("fes").single().id)
        assertEquals("fez", MoroccoCities.search("فاس").single().id)
        assertTrue(MoroccoCities.search("casa").any { it.id == "casablanca" })
        assertTrue(MoroccoCities.search("الدار البيضاء").any { it.id == "casablanca" })
        assertTrue(MoroccoCities.search("laayoune").any { it.id == "laayoune" })
        assertTrue(MoroccoCities.search("").isEmpty())
    }

    @Test
    fun nearestTownAndBounds() {
        val (city, km) = MoroccoCities.nearest(GeoPoint(33.53, -5.12))
        assertEquals("ifrane", city.id)
        assertTrue(km < 2)
        assertFalse(MoroccoBounds.contains(GeoPoint(48.85, 2.35)), "Paris is outside")
        assertTrue(MoroccoBounds.contains(GeoPoint(23.68, -15.96)), "Dakhla is inside")
    }
}
