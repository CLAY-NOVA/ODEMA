package ma.odema.core.farm

import kotlinx.serialization.Serializable
import ma.odema.core.GeoPoint
import ma.odema.core.Lang
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.round

/** A field or farm block, usually imported from a Google Earth KML. */
@Serializable
data class Parcel(
    val id: String,
    val name: String,
    /** KML folder or farm the parcel belongs to. */
    val group: String? = null,
    /** Outer boundary, if known (closed or open ring). */
    val polygon: List<GeoPoint> = emptyList(),
    /** Explicit location; otherwise the polygon's centroid. */
    val center: GeoPoint? = null,
    /** Official surface ("Sup."), which wins over the computed polygon area. */
    val declaredAreaHa: Double? = null,
    val notes: String = "",
    val alertsEnabled: Boolean = true,
) {
    val computedAreaHa: Double? get() = if (polygon.size >= 3) Geo.areaHa(polygon) else null
    val areaHa: Double? get() = declaredAreaHa ?: computedAreaHa
    val location: GeoPoint? get() = center ?: polygon.takeIf { it.size >= 3 }?.let(Geo::centroid)

    /**
     * Parcels within ~2 km share one rainfall series: the reanalysis grids are 9–25 km,
     * so fetching them separately would only cost API quota.
     */
    val historyKey: String? get() = location?.let { historyKeyFor(it) }

    companion object {
        fun historyKeyFor(p: GeoPoint): String {
            fun r(v: Double) = round(v / 0.02) * 0.02
            return "%.2f_%.2f".format(Locale.ROOT, r(p.lat), r(p.lon))
        }
    }
}

/** What grows on (part of) a parcel during one agricultural campaign — one line of the assolement. */
@Serializable
data class CropEntry(
    val id: String,
    val parcelId: String,
    /** Campaign start year: 2025 means the 2025-2026 campaign (Sept 2025 – Aug 2026). */
    val campaign: Int,
    /** Sub-parcel / block label, e.g. "Alia 1". */
    val block: String = "",
    val crop: String = Crop.AUTRE.name,
    val variety: String = "",
    /** Seed generation for multiplication: G0–G4, R1, R2… */
    val generation: String = "",
    val areaHa: Double? = null,
    /** Epoch days. */
    val sowingDate: Long? = null,
    val harvestDate: Long? = null,
    /** Tonnes per hectare. */
    val yieldTPerHa: Double? = null,
    val notes: String = "",
) {
    val cropKind: Crop get() = Crop.of(crop)
}

/** A rain-gauge reading. A single day overrides the model; a period is compared with it. */
@Serializable
data class GaugeReading(
    val id: String,
    val parcelId: String,
    /** Last day covered (epoch day). */
    val date: Long,
    /** First day covered; equal to [date] for a daily reading. */
    val fromDate: Long = date,
    val mm: Double,
    val note: String = "",
) {
    val isDaily: Boolean get() = fromDate == date
}

@Serializable
data class FarmData(
    val parcels: List<Parcel> = emptyList(),
    val crops: List<CropEntry> = emptyList(),
    val gauges: List<GaugeReading> = emptyList(),
    val version: Int = 1,
)

enum class CropFamily { CEREAL, INDUSTRIAL, LEGUME, FORAGE, VEGETABLE, TREE, OILSEED, OTHER }

/** Crops grown in Moroccan farms, with a colour for maps and charts. */
enum class Crop(val emoji: String, val color: Long, val fr: String, val en: String, val ar: String, val family: CropFamily, val code: String) {
    BLE_TENDRE("🌾", 0xFFE0A800, "Blé tendre", "Soft wheat", "قمح طري", CropFamily.CEREAL, "BT"),
    BLE_DUR("🌾", 0xFFC27C0E, "Blé dur", "Durum wheat", "قمح صلب", CropFamily.CEREAL, "BD"),
    ORGE("🌾", 0xFFB59F3B, "Orge", "Barley", "شعير", CropFamily.CEREAL, "OR"),
    TRITICALE("🌾", 0xFF9E8A2E, "Triticale", "Triticale", "تريتيكال", CropFamily.CEREAL, "TR"),
    AVOINE("🌾", 0xFFBFA76F, "Avoine", "Oats", "شوفان", CropFamily.CEREAL, "AV"),
    MAIS("🌽", 0xFFF2C12E, "Maïs", "Maize", "ذرة", CropFamily.CEREAL, "MA"),
    BETTERAVE("🟣", 0xFF8E24AA, "Betterave à sucre", "Sugar beet", "الشمندر السكري", CropFamily.INDUSTRIAL, "BAS"),
    CANNE("🎋", 0xFF6D9F3A, "Canne à sucre", "Sugar cane", "قصب السكر", CropFamily.INDUSTRIAL, "CS"),
    FEVEROLE("🌱", 0xFF5D8C3A, "Féverole", "Faba bean", "الفول", CropFamily.LEGUME, "FV"),
    POIS("🌱", 0xFF7CB342, "Pois", "Peas", "البازلاء", CropFamily.LEGUME, "PP"),
    POIS_CHICHE("🌱", 0xFFA1887F, "Pois chiche", "Chickpea", "الحمص", CropFamily.LEGUME, "PC"),
    LENTILLE("🌱", 0xFF8D6E63, "Lentille", "Lentils", "العدس", CropFamily.LEGUME, "LE"),
    LUZERNE("🍃", 0xFF2E7D32, "Luzerne", "Alfalfa", "الفصة", CropFamily.FORAGE, "LU"),
    BERSIM("🍃", 0xFF43A047, "Bersim", "Berseem", "البرسيم", CropFamily.FORAGE, "BE"),
    COLZA("🌼", 0xFFFDD835, "Colza", "Rapeseed", "السلجم", CropFamily.OILSEED, "CO"),
    TOURNESOL("🌻", 0xFFFFB300, "Tournesol", "Sunflower", "عباد الشمس", CropFamily.OILSEED, "TO"),
    TOMATE("🍅", 0xFFE53935, "Tomate", "Tomato", "الطماطم", CropFamily.VEGETABLE, "TM"),
    POMME_DE_TERRE("🥔", 0xFFA1887F, "Pomme de terre", "Potato", "البطاطس", CropFamily.VEGETABLE, "PT"),
    OIGNON("🧅", 0xFFBA68C8, "Oignon", "Onion", "البصل", CropFamily.VEGETABLE, "OI"),
    PASTEQUE("🍉", 0xFF66BB6A, "Pastèque", "Watermelon", "البطيخ الأحمر", CropFamily.VEGETABLE, "PA"),
    MELON("🍈", 0xFFC0CA33, "Melon", "Melon", "البطيخ الأصفر", CropFamily.VEGETABLE, "ME"),
    FRAISE("🍓", 0xFFD81B60, "Fraise", "Strawberry", "الفراولة", CropFamily.VEGETABLE, "FR"),
    MARAICHAGE("🥬", 0xFF4CAF50, "Maraîchage", "Vegetables", "خضروات", CropFamily.VEGETABLE, "MR"),
    AGRUMES("🍊", 0xFFFB8C00, "Agrumes", "Citrus", "الحوامض", CropFamily.TREE, "AG"),
    OLIVIER("🫒", 0xFF558B2F, "Olivier", "Olive", "الزيتون", CropFamily.TREE, "OL"),
    AVOCATIER("🥑", 0xFF33691E, "Avocatier", "Avocado", "الأفوكادو", CropFamily.TREE, "AC"),
    AMANDIER("🌸", 0xFFF48FB1, "Amandier", "Almond", "اللوز", CropFamily.TREE, "AM"),
    COUVERT("🌿", 0xFF81C784, "Couvert végétal", "Cover crop", "غطاء نباتي", CropFamily.OTHER, "CV"),
    JACHERE("🟫", 0xFF9E9E9E, "Jachère", "Fallow", "أرض بور", CropFamily.OTHER, "JA"),
    AUTRE("📋", 0xFF78909C, "Autre", "Other", "أخرى", CropFamily.OTHER, "AU");

    fun name(lang: Lang): String = when (lang) { Lang.EN -> en; Lang.FR -> fr; Lang.AR -> ar }

    companion object {
        fun of(name: String?): Crop = entries.firstOrNull { it.name == name } ?: AUTRE
    }
}

/** Seed generations used in seed multiplication (G0 pre-basic → R2 certified). */
val SeedGenerations = listOf("", "G0", "G1", "G2", "G3", "G4", "R1", "R2")

/** Moroccan agricultural campaigns run from 1 September to 31 August. */
object Campaigns {
    fun of(date: LocalDate): Int = if (date.monthValue >= 9) date.year else date.year - 1
    fun ofDay(epochDay: Long): Int = of(LocalDate.ofEpochDay(epochDay))
    fun start(campaign: Int): LocalDate = LocalDate.of(campaign, 9, 1)
    fun end(campaign: Int): LocalDate = LocalDate.of(campaign + 1, 8, 31)
    fun label(campaign: Int): String = "$campaign-${campaign + 1}"
    fun current(today: LocalDate = LocalDate.now()): Int = of(today)
}

/** Planar geometry on a local projection — accurate to well under 1% for farm-sized polygons. */
object Geo {
    private const val R = 6_371_008.8

    private fun ring(points: List<GeoPoint>): List<GeoPoint> =
        if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points

    /** Area in hectares (shoelace formula on an equirectangular projection centred on the polygon). */
    fun areaHa(points: List<GeoPoint>): Double {
        val p = ring(points)
        if (p.size < 3) return 0.0
        val lat0 = Math.toRadians(p.map { it.lat }.average())
        val xs = p.map { Math.toRadians(it.lon) * cos(lat0) * R }
        val ys = p.map { Math.toRadians(it.lat) * R }
        var sum = 0.0
        for (i in p.indices) {
            val j = (i + 1) % p.size
            sum += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return abs(sum) / 2 / 10_000
    }

    /** Area-weighted centroid; falls back to the vertex average for degenerate rings. */
    fun centroid(points: List<GeoPoint>): GeoPoint {
        val p = ring(points)
        val lat0 = p.map { it.lat }.average()
        val lon0 = p.map { it.lon }.average()
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        val k = cos(Math.toRadians(lat0))
        for (i in p.indices) {
            val j = (i + 1) % p.size
            val x0 = (p[i].lon - lon0) * k
            val y0 = p[i].lat - lat0
            val x1 = (p[j].lon - lon0) * k
            val y1 = p[j].lat - lat0
            val f = x0 * y1 - x1 * y0
            a += f
            cx += (x0 + x1) * f
            cy += (y0 + y1) * f
        }
        if (abs(a) < 1e-15) return GeoPoint(lat0, lon0)
        return GeoPoint(lat0 + cy / (3 * a), lon0 + cx / (3 * a) / k)
    }

    fun contains(points: List<GeoPoint>, q: GeoPoint): Boolean {
        val p = ring(points)
        var inside = false
        var j = p.lastIndex
        for (i in p.indices) {
            if ((p[i].lat > q.lat) != (p[j].lat > q.lat) &&
                q.lon < (p[j].lon - p[i].lon) * (q.lat - p[i].lat) / (p[j].lat - p[i].lat) + p[i].lon
            ) inside = !inside
            j = i
        }
        return inside
    }
}
