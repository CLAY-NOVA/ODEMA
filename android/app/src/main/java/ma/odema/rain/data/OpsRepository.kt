package ma.odema.rain.data

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import ma.odema.core.OdemaJson
import ma.odema.core.ops.Asset
import ma.odema.core.ops.Attendance
import ma.odema.core.ops.Col
import ma.odema.core.ops.FarmPlan
import ma.odema.core.ops.Harvest
import ma.odema.core.ops.Incident
import ma.odema.core.ops.OpsAnalytics
import ma.odema.core.ops.OpsCollections
import ma.odema.core.ops.OpsData
import ma.odema.core.ops.Photo
import ma.odema.core.ops.Product
import ma.odema.core.ops.Reading
import ma.odema.core.ops.Rec
import ma.odema.core.ops.Staff
import ma.odema.core.ops.StockMove
import ma.odema.core.ops.Work
import ma.odema.rain.OdemaApplication
import java.io.File
import java.util.UUID

data class OpsState(
    val loaded: Boolean = false,
    val data: OpsData = OpsData(),
    /** The farm plan bundled in the APK (assets/odema_plan.json, private), if any. */
    val plan: FarmPlan? = null,
)

/**
 * Field work, breakdowns, equipment, stock, harvests and staff. Saved on the phone first;
 * when the team sync is on, every change is also sent to the farm's cloud and coworkers'
 * changes arrive here through [applyRemote].
 */
class OpsRepository(private val app: OdemaApplication) {
    private val _state = MutableStateFlow(OpsState())
    val state: StateFlow<OpsState> = _state.asStateFlow()

    /** Receives every local change (collection, id, JSON or null when deleted). Set by the team sync. */
    @Volatile var sink: ((Col, String, String?) -> Unit)? = null

    /** Name written on records ("updated by"). */
    @Volatile var userName: () -> String = { "" }

    private val loadLock = Mutex()
    private val writeLock = Mutex()
    private val dir get() = File(app.filesDir, "odema").apply { mkdirs() }
    private val dataFile get() = File(dir, "ops.json")
    private val photoFile get() = File(dir, "photos.json")
    private val photoSerializer = ListSerializer(Photo.serializer())

    fun newId(): String = UUID.randomUUID().toString()

    suspend fun ensureLoaded() {
        if (_state.value.loaded) return
        loadLock.withLock {
            if (_state.value.loaded) return
            withContext(Dispatchers.IO) {
                val plan = runCatching {
                    app.assets.open("odema_plan.json").bufferedReader().use { OdemaJson.decodeFromString(FarmPlan.serializer(), it.readText()) }
                }.getOrNull()
                var data = dataFile.takeIf { it.exists() }?.let { f -> runCatching { OdemaJson.decodeFromString(OpsData.serializer(), f.readText()) }.getOrNull() }
                val photos = photoFile.takeIf { it.exists() }?.let { f -> runCatching { OdemaJson.decodeFromString(photoSerializer, f.readText()) }.getOrNull() }.orEmpty()
                if (data == null) {
                    // First start: the wells, postes, basins and pivots drawn on the plan become the equipment list.
                    data = OpsData(assets = plan?.assets.orEmpty())
                    write(dataFile, data)
                }
                _state.value = OpsState(loaded = true, data = data.copy(photos = photos), plan = plan)
            }
        }
    }

    private fun write(file: File, data: OpsData) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(OdemaJson.encodeToString(OpsData.serializer(), data.copy(photos = emptyList())))
        tmp.renameTo(file)
    }

    private suspend fun save(old: OpsData, new: OpsData) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            write(dataFile, new)
            if (old.photos !== new.photos) {
                val tmp = File(photoFile.path + ".tmp")
                tmp.writeText(OdemaJson.encodeToString(photoSerializer, new.photos))
                tmp.renameTo(photoFile)
            }
        }
    }

    private suspend fun edit(transform: (OpsData) -> OpsData) {
        ensureLoaded()
        var old = OpsData()
        var new = OpsData()
        _state.update { s -> old = s.data; new = transform(s.data); s.copy(data = new) }
        save(old, new)
        sink?.let { push -> OpsCollections.diff(old, new).forEach { (col, id, json) -> push(col, id, json) } }
    }

    /** A coworker's change. Nothing is sent back. */
    fun applyRemote(col: Col, id: String, json: String?) {
        var old = OpsData()
        var new = OpsData()
        _state.update { s -> old = s.data; new = OpsCollections.apply(s.data, col, id, json); s.copy(data = new) }
        if (old !== new) app.scope.launch { save(old, new) }
    }

    /** Local records, as JSON, for the first upload when the team sync is switched on. */
    fun localDocs(col: Col): Map<String, Pair<Long, String>> =
        OpsCollections.list(_state.value.data, col).associate { it.id to (it.updatedAt to OpsCollections.encode(col, it)) }

    private fun now() = System.currentTimeMillis()
    private fun by() = userName()

    private fun <T : Rec> upsert(list: List<T>, item: T): List<T> =
        if (list.any { it.id == item.id }) list.map { if (it.id == item.id) item else it } else list + item

    // Field work (and the stock it uses)

    suspend fun saveWork(work: Work) {
        val w = work.copy(updatedAt = now(), updatedBy = by(), createdBy = work.createdBy.ifBlank { by() })
        val moves = OpsAnalytics.movesForWork(w, w.updatedAt, w.updatedBy)
        edit { d -> d.copy(works = upsert(d.works, w), moves = d.moves.filterNot { it.workId == w.id } + moves) }
    }

    suspend fun deleteWork(id: String) = edit { d ->
        d.copy(works = d.works.filterNot { it.id == id }, moves = d.moves.filterNot { it.workId == id }, photos = d.photos.filterNot { it.ownerId == id })
    }

    // Breakdowns

    suspend fun saveIncident(incident: Incident) = edit { d ->
        d.copy(incidents = upsert(d.incidents, incident.copy(updatedAt = now(), updatedBy = by(), reportedBy = incident.reportedBy.ifBlank { by() })))
    }

    suspend fun deleteIncident(id: String) = edit { d ->
        d.copy(incidents = d.incidents.filterNot { it.id == id }, photos = d.photos.filterNot { it.ownerId == id })
    }

    // Equipment and readings

    suspend fun saveAsset(asset: Asset) = edit { d -> d.copy(assets = upsert(d.assets, asset.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteAsset(id: String) = edit { d -> d.copy(assets = d.assets.filterNot { it.id == id }, readings = d.readings.filterNot { it.assetId == id }) }

    suspend fun saveReading(reading: Reading) = edit { d -> d.copy(readings = upsert(d.readings, reading.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteReading(id: String) = edit { d -> d.copy(readings = d.readings.filterNot { it.id == id }) }

    // Stock

    suspend fun saveProduct(product: Product) = edit { d -> d.copy(products = upsert(d.products, product.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteProduct(id: String) = edit { d -> d.copy(products = d.products.filterNot { it.id == id }, moves = d.moves.filterNot { it.productId == id }) }

    suspend fun saveMove(move: StockMove) = edit { d -> d.copy(moves = upsert(d.moves, move.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteMove(id: String) = edit { d -> d.copy(moves = d.moves.filterNot { it.id == id }) }

    // Harvests

    suspend fun saveHarvest(harvest: Harvest) = edit { d -> d.copy(harvests = upsert(d.harvests, harvest.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteHarvest(id: String) = edit { d -> d.copy(harvests = d.harvests.filterNot { it.id == id }) }

    // Staff

    suspend fun saveStaff(staff: Staff) = edit { d -> d.copy(staff = upsert(d.staff, staff.copy(updatedAt = now(), updatedBy = by()))) }

    suspend fun deleteStaff(id: String) = edit { d -> d.copy(staff = d.staff.filterNot { it.id == id }) }

    suspend fun saveAttendance(sheet: Attendance) = edit { d -> d.copy(attendance = upsert(d.attendance, sheet.copy(updatedAt = now(), updatedBy = by()))) }

    // Photos

    suspend fun addPhoto(ownerId: String, jpegBase64: String) = edit { d ->
        d.copy(photos = d.photos + Photo(newId(), ownerId, jpegBase64, now(), now(), by()))
    }

    suspend fun deletePhoto(id: String) = edit { d -> d.copy(photos = d.photos.filterNot { it.id == id }) }

    @VisibleForTesting
    internal fun replaceStateForTests(transform: (OpsState) -> OpsState) = _state.update(transform)
}
