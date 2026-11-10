package ma.odema.core.ops

import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import ma.odema.core.OdemaJson

/**
 * Day-to-day farm management records: equipment, field work, breakdowns, stock, harvests and staff.
 * Every record carries who changed it last and when, so phones can merge each other's edits
 * (the newest change wins) and show an activity feed.
 */
interface Rec {
    val id: String
    val updatedAt: Long
    val updatedBy: String
}

enum class AssetKind(val emoji: String) {
    WELL("💧"), POWER("⚡"), BASIN("🟦"), PIVOT("🔄"), PUMP("⚙️"), TRACTOR("🚜"), VEHICLE("🚙"), EQUIPMENT("🧰"), BUILDING("🏠"), OTHER("📦");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

/** Equipment and infrastructure: wells, power stations (postes), basins, pivots, tractors… */
@Serializable
data class Asset(
    override val id: String,
    val kind: String = AssetKind.OTHER.name,
    val name: String,
    val code: String = "",
    /** Inactive wells or equipment out of service for good. Breakdowns are tracked as incidents. */
    val active: Boolean = true,
    /** Position on the farm plan (plan units), if drawn there. */
    val planX: Double? = null,
    val planY: Double? = null,
    val parcelId: String? = null,
    /** ONEE contract number (power stations). */
    val contract: String = "",
    val powerKva: Double? = null,
    val depthM: Double? = null,
    val flowM3h: Double? = null,
    val pumpKw: Double? = null,
    val capacityM3: Double? = null,
    val radiusM: Double? = null,
    val notes: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val assetKind: AssetKind get() = AssetKind.of(kind)
}

enum class ReadingKind(val unit: String) {
    METER_KWH("kWh"), FLOW("m³/h"), LEVEL("%"), PUMP_HOURS("h"), WATER_M3("m³"), OTHER("");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

/** A meter or gauge reading on an asset (electricity index, well flow, basin level…). */
@Serializable
data class Reading(
    override val id: String,
    val assetId: String,
    /** Epoch day. */
    val date: Long,
    val kind: String = ReadingKind.OTHER.name,
    val value: Double,
    val note: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val readingKind: ReadingKind get() = ReadingKind.of(kind)
}

enum class WorkType(val emoji: String) {
    SOIL("🚜"), SOWING("🌱"), FERTILIZER("🧪"), SPRAY("💨"), WEEDING("🌿"), IRRIGATION("💧"),
    HARVEST("🌾"), PRUNING("✂️"), MAINTENANCE("🔧"), TRANSPORT("🚚"), OTHER("📋");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

enum class WorkStatus { PLANNED, IN_PROGRESS, DONE;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: PLANNED }
}

@Serializable
data class InputUse(val productId: String, val qty: Double)

/** A field operation, planned or done: tillage, sowing, fertiliser, spraying, irrigation, harvest… */
@Serializable
data class Work(
    override val id: String,
    val type: String = WorkType.OTHER.name,
    val status: String = WorkStatus.PLANNED.name,
    /** Epoch day (planned or actual start). */
    val date: Long,
    val endDate: Long? = null,
    val parcelId: String? = null,
    val block: String = "",
    val title: String = "",
    /** Products used; taken out of stock once the work is done. */
    val inputs: List<InputUse> = emptyList(),
    val areaHa: Double? = null,
    val workers: Int? = null,
    val hours: Double? = null,
    val machine: String = "",
    val responsible: String = "",
    /** Other costs in MAD (service, rental…), on top of the products. */
    val cost: Double? = null,
    val notes: String = "",
    val createdBy: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val workType: WorkType get() = WorkType.of(type)
    val workStatus: WorkStatus get() = WorkStatus.of(status)
    val done: Boolean get() = workStatus == WorkStatus.DONE
    fun overdue(today: Long): Boolean = !done && (endDate ?: date) < today
}

enum class Priority { NORMAL, URGENT;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: NORMAL }
}

enum class IncidentStatus { OPEN, IN_PROGRESS, RESOLVED;
    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OPEN }
}

/** A breakdown or problem to fix: pump down, power cut, leaking pipe, broken tractor… */
@Serializable
data class Incident(
    override val id: String,
    val title: String,
    val assetId: String? = null,
    val parcelId: String? = null,
    val description: String = "",
    val priority: String = Priority.NORMAL.name,
    val status: String = IncidentStatus.OPEN.name,
    /** Epoch milliseconds. */
    val reportedAt: Long,
    val reportedBy: String = "",
    val assignedTo: String = "",
    val resolvedAt: Long? = null,
    val resolution: String = "",
    val cost: Double? = null,
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val incidentStatus: IncidentStatus get() = IncidentStatus.of(status)
    val urgent: Boolean get() = Priority.of(priority) == Priority.URGENT
    val open: Boolean get() = incidentStatus != IncidentStatus.RESOLVED
}

enum class ProductCategory(val emoji: String) {
    FERTILIZER("🧪"), PESTICIDE("🛡️"), SEED("🌱"), FUEL("⛽"), PART("🔩"), IRRIGATION("💧"), OTHER("📦");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

/** A stock item: fertiliser, pesticide, seed, fuel, spare part… */
@Serializable
data class Product(
    override val id: String,
    val name: String,
    val category: String = ProductCategory.OTHER.name,
    /** kg, L, t, unité, sac… */
    val unit: String = "kg",
    /** Warn when the stock falls to this level. */
    val minStock: Double? = null,
    val notes: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val productCategory: ProductCategory get() = ProductCategory.of(category)
}

/** Stock in (positive quantity) or out (negative). */
@Serializable
data class StockMove(
    override val id: String,
    val productId: String,
    /** Epoch day. */
    val date: Long,
    val qty: Double,
    /** MAD per unit (purchases). */
    val unitPrice: Double? = null,
    /** Supplier or recipient. */
    val partner: String = "",
    /** Set when the move was created by a field work. */
    val workId: String? = null,
    val parcelId: String? = null,
    val note: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec

/** A harvest delivery: so many tonnes from a block, sold to someone. */
@Serializable
data class Harvest(
    override val id: String,
    val parcelId: String,
    val block: String = "",
    val campaign: Int,
    val crop: String = "",
    /** Epoch day. */
    val date: Long,
    val quantityT: Double,
    val quality: String = "",
    val buyer: String = "",
    val pricePerT: Double? = null,
    val note: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec

enum class StaffRole(val emoji: String) {
    MANAGER("👔"), FOREMAN("🧑‍🌾"), WORKER("👷"), GUARD("🛡️"), DRIVER("🚚"), TRACTOR_DRIVER("🚜"), MECHANIC("🔧"), IRRIGATOR("💧"), OTHER("👤");

    companion object { fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OTHER }
}

@Serializable
data class Staff(
    override val id: String,
    val name: String,
    val role: String = StaffRole.WORKER.name,
    val phone: String = "",
    /** MAD per day. */
    val dailyRate: Double? = null,
    val active: Boolean = true,
    val notes: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    val staffRole: StaffRole get() = StaffRole.of(role)
}

/** One day's attendance sheet (pointage): who was there, casual workers and guards. */
@Serializable
data class Attendance(
    override val id: String,
    /** Epoch day. */
    val date: Long,
    val present: List<String> = emptyList(),
    val casuals: Int = 0,
    /** MAD per casual worker per day. */
    val casualRate: Double? = null,
    val guardDay: String? = null,
    val guardNight: String? = null,
    val notes: String = "",
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec {
    companion object { fun idFor(day: Long) = "day-$day" }
}

/** A photo attached to a record, stored as a small JPEG so it syncs like any other record. */
@Serializable
data class Photo(
    override val id: String,
    val ownerId: String,
    val jpegBase64: String,
    val takenAt: Long,
    override val updatedAt: Long = 0,
    override val updatedBy: String = "",
) : Rec

@Serializable
data class OpsData(
    val assets: List<Asset> = emptyList(),
    val readings: List<Reading> = emptyList(),
    val works: List<Work> = emptyList(),
    val incidents: List<Incident> = emptyList(),
    val products: List<Product> = emptyList(),
    val moves: List<StockMove> = emptyList(),
    val harvests: List<Harvest> = emptyList(),
    val staff: List<Staff> = emptyList(),
    val attendance: List<Attendance> = emptyList(),
    val photos: List<Photo> = emptyList(),
    val version: Int = 1,
) {
    fun asset(id: String?) = assets.firstOrNull { it.id == id }
    fun product(id: String?) = products.firstOrNull { it.id == id }
    fun staffMember(id: String?) = staff.firstOrNull { it.id == id }
    fun photosOf(ownerId: String) = photos.filter { it.ownerId == ownerId }.sortedBy { it.takenAt }
}

/**
 * The synced collections. Each record travels as JSON, so the cloud side needs no schema and
 * older app versions keep unknown fields out of their way.
 */
enum class Col(val key: String) {
    PARCELS("parcels"), CROPS("crops"), GAUGES("gauges"),
    ASSETS("assets"), READINGS("readings"), WORKS("works"), INCIDENTS("incidents"),
    PRODUCTS("products"), MOVES("moves"), HARVESTS("harvests"), STAFF("staff"), ATTENDANCE("attendance"), PHOTOS("photos");

    val isFarm: Boolean get() = this == PARCELS || this == CROPS || this == GAUGES

    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

/** Generic, typed access to [OpsData] by collection, for syncing. */
object OpsCollections {
    private fun <T : Rec> upsert(list: List<T>, item: T): List<T> =
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item

    fun list(data: OpsData, col: Col): List<Rec> = when (col) {
        Col.ASSETS -> data.assets
        Col.READINGS -> data.readings
        Col.WORKS -> data.works
        Col.INCIDENTS -> data.incidents
        Col.PRODUCTS -> data.products
        Col.MOVES -> data.moves
        Col.HARVESTS -> data.harvests
        Col.STAFF -> data.staff
        Col.ATTENDANCE -> data.attendance
        Col.PHOTOS -> data.photos
        else -> emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    fun serializer(col: Col): KSerializer<Rec> = when (col) {
        Col.ASSETS -> Asset.serializer()
        Col.READINGS -> Reading.serializer()
        Col.WORKS -> Work.serializer()
        Col.INCIDENTS -> Incident.serializer()
        Col.PRODUCTS -> Product.serializer()
        Col.MOVES -> StockMove.serializer()
        Col.HARVESTS -> Harvest.serializer()
        Col.STAFF -> Staff.serializer()
        Col.ATTENDANCE -> Attendance.serializer()
        Col.PHOTOS -> Photo.serializer()
        else -> error("not an ops collection: $col")
    } as KSerializer<Rec>

    fun encode(col: Col, rec: Rec): String = OdemaJson.encodeToString(serializer(col), rec)

    /** Inserts or replaces a record; [json] null removes it. Older copies never replace newer ones. */
    fun apply(data: OpsData, col: Col, id: String, json: String?): OpsData {
        if (json == null) return remove(data, col, id)
        val rec = runCatching { OdemaJson.decodeFromString(serializer(col), json) }.getOrNull() ?: return data
        val current = list(data, col).firstOrNull { it.id == id }
        if (current != null && current.updatedAt > rec.updatedAt) return data
        return put(data, col, rec)
    }

    fun put(data: OpsData, col: Col, rec: Rec): OpsData = when (col) {
        Col.ASSETS -> data.copy(assets = upsert(data.assets, rec as Asset))
        Col.READINGS -> data.copy(readings = upsert(data.readings, rec as Reading))
        Col.WORKS -> data.copy(works = upsert(data.works, rec as Work))
        Col.INCIDENTS -> data.copy(incidents = upsert(data.incidents, rec as Incident))
        Col.PRODUCTS -> data.copy(products = upsert(data.products, rec as Product))
        Col.MOVES -> data.copy(moves = upsert(data.moves, rec as StockMove))
        Col.HARVESTS -> data.copy(harvests = upsert(data.harvests, rec as Harvest))
        Col.STAFF -> data.copy(staff = upsert(data.staff, rec as Staff))
        Col.ATTENDANCE -> data.copy(attendance = upsert(data.attendance, rec as Attendance))
        Col.PHOTOS -> data.copy(photos = upsert(data.photos, rec as Photo))
        else -> data
    }

    fun remove(data: OpsData, col: Col, id: String): OpsData = when (col) {
        Col.ASSETS -> data.copy(assets = data.assets.filterNot { it.id == id })
        Col.READINGS -> data.copy(readings = data.readings.filterNot { it.id == id })
        Col.WORKS -> data.copy(works = data.works.filterNot { it.id == id })
        Col.INCIDENTS -> data.copy(incidents = data.incidents.filterNot { it.id == id })
        Col.PRODUCTS -> data.copy(products = data.products.filterNot { it.id == id })
        Col.MOVES -> data.copy(moves = data.moves.filterNot { it.id == id })
        Col.HARVESTS -> data.copy(harvests = data.harvests.filterNot { it.id == id })
        Col.STAFF -> data.copy(staff = data.staff.filterNot { it.id == id })
        Col.ATTENDANCE -> data.copy(attendance = data.attendance.filterNot { it.id == id })
        Col.PHOTOS -> data.copy(photos = data.photos.filterNot { it.id == id })
        else -> data
    }

    /** Records that differ between two snapshots: (collection, id, json or null when deleted). */
    fun diff(old: OpsData, new: OpsData): List<Triple<Col, String, String?>> = Col.entries.filterNot { it.isFarm }.flatMap { col ->
        val before = list(old, col).associateBy { it.id }
        val after = list(new, col).associateBy { it.id }
        after.values.filter { before[it.id] != it }.map { Triple(col, it.id, encode(col, it)) } +
            before.keys.filter { it !in after }.map { Triple(col, it, null) }
    }
}
