package ma.odema.core.farm

import ma.odema.core.GeoPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One placemark read from a KML/KMZ file. */
data class KmlPlacemark(
    val name: String,
    val folder: String?,
    /** Largest outer ring if the placemark has polygons. */
    val polygon: List<GeoPoint>,
    /** Point geometry, if any. */
    val point: GeoPoint?,
    val description: String?,
) {
    val location: GeoPoint? get() = point ?: polygon.takeIf { it.size >= 3 }?.let(Geo::centroid)
    val areaHa: Double? get() = if (polygon.size >= 3) Geo.areaHa(polygon) else null
}

/**
 * Reads Google Earth exports (KML or zipped KMZ): placemarks with polygons, points or
 * multi-geometries, inside nested folders. Namespace-agnostic, so KML 2.1/2.2 and
 * gx extensions all work.
 */
object Kml {
    fun parse(bytes: ByteArray): List<KmlPlacemark> {
        val xml = if (isZip(bytes)) unzipKml(bytes) else bytes
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // No external entities: the file comes from outside the app.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        val out = mutableListOf<KmlPlacemark>()
        walk(doc.documentElement, null, out)
        return out
    }

    private fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    private fun unzipKml(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var fallback: ByteArray? = null
            while (entry != null) {
                if (entry.name.endsWith(".kml", ignoreCase = true)) {
                    val data = zip.readBytes()
                    if (entry.name.equals("doc.kml", ignoreCase = true)) return data
                    if (fallback == null) fallback = data
                }
                entry = zip.nextEntry
            }
            return fallback ?: throw IllegalArgumentException("No .kml file inside the KMZ")
        }
    }

    private fun Node.local(): String = (localName ?: nodeName).substringAfter(':')

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.local() == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    private fun Element.descendants(name: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(e: Element) {
            for (i in 0 until e.childNodes.length) {
                val c = e.childNodes.item(i) as? Element ?: continue
                if (c.local() == name) result += c
                visit(c)
            }
        }
        visit(this)
        return result
    }

    private fun walk(e: Element, folder: String?, out: MutableList<KmlPlacemark>) {
        when (e.local()) {
            "Placemark" -> placemark(e, folder)?.let(out::add)
            else -> {
                val name = if (e.local() == "Folder") e.child("name")?.textContent?.trim()?.takeIf { it.isNotEmpty() } else null
                for (i in 0 until e.childNodes.length) {
                    val c = e.childNodes.item(i) as? Element ?: continue
                    walk(c, name ?: folder, out)
                }
            }
        }
    }

    private fun placemark(e: Element, folder: String?): KmlPlacemark? {
        val name = e.child("name")?.textContent?.trim().orEmpty()
        val rings = e.descendants("outerBoundaryIs").mapNotNull { it.descendants("coordinates").firstOrNull() }
            .map { coordinates(it.textContent) }
            .filter { it.size >= 3 }
        // A LineString drawn around a field is treated as its boundary when closed.
        val lines = e.descendants("LineString").mapNotNull { it.descendants("coordinates").firstOrNull() }
            .map { coordinates(it.textContent) }
            .filter { it.size >= 4 && it.first() == it.last() }
        val polygon = (rings + lines).maxByOrNull { Geo.areaHa(it) }.orEmpty()
        val point = e.descendants("Point").firstOrNull()?.descendants("coordinates")?.firstOrNull()
            ?.let { coordinates(it.textContent).firstOrNull() }
        if (polygon.isEmpty() && point == null) return null
        val description = e.child("description")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
        return KmlPlacemark(name.ifEmpty { folder ?: "?" }, folder, polygon, point, description)
    }

    /** "lon,lat[,alt] lon,lat[,alt] …" → points. */
    fun coordinates(text: String): List<GeoPoint> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(",")
            val lon = parts.getOrNull(0)?.toDoubleOrNull()
            val lat = parts.getOrNull(1)?.toDoubleOrNull()
            if (lon != null && lat != null && lat in -90.0..90.0 && lon in -180.0..180.0) GeoPoint(lat, lon) else null
        }
}

/** How an import changed the parcel list. */
data class KmlMerge(val parcels: List<Parcel>, val added: Int, val updated: Int)

object KmlImport {
    /**
     * Merges placemarks into existing parcels: same name (ignoring case and accents)
     * updates the geometry and keeps crops, gauges and history; new names are added.
     */
    fun merge(existing: List<Parcel>, placemarks: List<KmlPlacemark>, newId: () -> String): KmlMerge {
        val result = existing.toMutableList()
        var added = 0
        var updated = 0
        for (pm in placemarks) {
            val key = normalizeName(pm.name)
            val index = result.indexOfFirst { normalizeName(it.name) == key }
            if (index >= 0) {
                val old = result[index]
                result[index] = old.copy(
                    polygon = pm.polygon.ifEmpty { old.polygon },
                    center = if (pm.polygon.isEmpty()) pm.point ?: old.center else old.center,
                    group = pm.folder ?: old.group,
                )
                updated++
            } else {
                result += Parcel(
                    id = newId(),
                    name = pm.name,
                    group = pm.folder,
                    polygon = pm.polygon,
                    center = if (pm.polygon.isEmpty()) pm.point else null,
                    notes = pm.description?.let(::stripHtml).orEmpty(),
                )
                added++
            }
        }
        return KmlMerge(result, added, updated)
    }

    fun normalizeName(s: String): String = ma.odema.core.normalize(s).replace(" ", "")

    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(500)
}
